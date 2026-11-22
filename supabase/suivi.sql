-- Carnet Mobile Money · version 0.10 : paiement en un geste, validation automatique des recharges,
-- suivi de l'argent (retraits répartis, salaire ou argent reçu suivi jusqu'à la dernière dépense),
-- dettes et créances par personne.
-- À exécuter UNE fois dans Supabase → SQL Editor, après categories.sql. Peut être relancé sans danger.
--
-- Recharge :
--  - L'application ouvre le menu de l'opérateur (code USSD réglable ici), le client paie avec son code secret
--    (jamais vu par l'application), puis l'application lit le SMS de l'opérateur et déclare seule l'ID.
--  - Le téléphone marchand (compte superadmin) envoie les transferts qu'il reçoit (table recu). Une recharge
--    déclarée est validée automatiquement quand un transfert reçu correspond :
--      1. même opérateur, même ID et même montant ;
--      2. à défaut, même opérateur, même montant, envoyé depuis le numéro du compte, à moins de 3 heures,
--         et un seul transfert reçu possible.
--    Sinon elle reste en attente et le superadmin la valide à la main comme avant.

-- ---------- Paramètres : codes du menu de paiement ----------
-- {numero} et {montant} sont remplacés par l'application si le code les contient (raccourci complet).
insert into public.parametre (cle, valeur) values ('ussd_om', '#150#'), ('ussd_momo', '*126#')
on conflict (cle) do nothing;

create or replace function public.admin_parametre(p_cle text, p_valeur text) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  if p_cle not in ('prix_export_petit', 'prix_export_grand', 'seuil_export', 'exports_offerts', 'recharge_min',
                   'numero_om', 'numero_momo', 'nom_marchand', 'ussd_om', 'ussd_momo') then
    raise exception 'Paramètre inconnu.';
  end if;
  if p_cle in ('ussd_om', 'ussd_momo') and coalesce(p_valeur, '') !~ '^[*#][0-9*#{}a-z]*#$' then
    raise exception 'Code USSD invalide : il commence par * ou # et finit par #.';
  end if;
  insert into public.parametre (cle, valeur) values (p_cle, coalesce(p_valeur, ''))
  on conflict (cle) do update set valeur = excluded.valeur;
end $$;

-- ---------- Transferts reçus par le téléphone marchand ----------

alter table public.recharge add column if not exists auto boolean not null default false;

create table if not exists public.recu (
  id bigserial primary key,
  operateur text not null check (operateur in ('OM', 'MOMO')),
  reference text not null,
  montant integer not null,
  numero text,
  quand timestamptz not null,
  recharge_id bigint references public.recharge (id) on delete set null,
  cree timestamptz not null default now(),
  unique (operateur, reference)
);
create index if not exists recu_libre on public.recu (operateur, montant) where recharge_id is null;
create index if not exists recu_recharge on public.recu (recharge_id);
alter table public.recu enable row level security;
revoke all on public.recu from anon, authenticated;

create or replace function public.ref_norm(p text) returns text
language sql immutable set search_path = public as $$ select upper(regexp_replace(coalesce(p, ''), '\s', '', 'g')) $$;

-- Rapproche les recharges en attente et les transferts reçus libres. Renvoie le nombre de recharges validées.
create or replace function public.rapprocher_recharges() returns integer
language plpgsql security definer set search_path = public as $$
declare r record; v_recu bigint; v_n integer := 0; v_nb integer;
begin
  for r in select x.*, p.telephone from public.recharge x left join public.profil p on p.user_id = x.user_id
            where x.statut = 'en_attente' and x.operateur in ('OM', 'MOMO') order by x.cree for update of x loop
    v_recu := null;
    select id into v_recu from public.recu
     where recharge_id is null and operateur = r.operateur and reference = public.ref_norm(r.reference) and montant = r.montant;
    if v_recu is null and r.telephone is not null then
      select count(*), min(id) into v_nb, v_recu from public.recu
       where recharge_id is null and operateur = r.operateur and montant = r.montant
         and right(regexp_replace(coalesce(numero, ''), '\D', '', 'g'), 9) = r.telephone
         and quand between r.cree - interval '3 hours' and r.cree + interval '3 hours';
      if v_nb <> 1 then v_recu := null; end if;
    end if;
    if v_recu is not null then
      update public.recu set recharge_id = r.id where id = v_recu;
      update public.recharge set statut = 'valide', auto = true, traite = now(), motif = 'Validée automatiquement' where id = r.id;
      update public.profil set solde = solde + r.montant, maj = now() where user_id = r.user_id;
      v_n := v_n + 1;
    end if;
  end loop;
  return v_n;
end $$;

-- Le téléphone marchand envoie ses transferts reçus : [{"operateur","reference","montant","numero","quand"(ms)}].
create or replace function public.admin_signaler_recus(p_recus json) returns json
language plpgsql security definer set search_path = public as $$
declare e json; v_ins integer := 0; v_c integer;
begin
  perform public.verifier_admin();
  for e in select * from json_array_elements(coalesce(p_recus, '[]'::json)) loop
    if (e->>'operateur') not in ('OM', 'MOMO') or coalesce(e->>'reference', '') = '' or coalesce((e->>'montant')::integer, 0) <= 0 then
      continue;
    end if;
    insert into public.recu (operateur, reference, montant, numero, quand)
    values (e->>'operateur', public.ref_norm(e->>'reference'), (e->>'montant')::integer, nullif(e->>'numero', ''),
            to_timestamp(coalesce((e->>'quand')::bigint, (extract(epoch from now()) * 1000)::bigint) / 1000.0))
    on conflict (operateur, reference) do nothing;
    get diagnostics v_c = row_count;
    v_ins := v_ins + v_c;
  end loop;
  return json_build_object('recus', v_ins, 'validees', public.rapprocher_recharges());
end $$;

-- Transferts reçus récents, pour le tableau de bord (ceux qui n'ont pas trouvé de recharge en premier).
create or replace function public.admin_recus(p_jours integer default 30) returns table (
  id bigint, operateur text, reference text, montant integer, numero text, quand timestamptz,
  recharge_id bigint, telephone text) language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return query
    select r.id, r.operateur, r.reference, r.montant, r.numero, r.quand, r.recharge_id, p.telephone
      from public.recu r left join public.recharge x on x.id = r.recharge_id left join public.profil p on p.user_id = x.user_id
     where r.cree > now() - make_interval(days => greatest(1, coalesce(p_jours, 30)))
     order by (r.recharge_id is null) desc, r.quand desc limit 500;
end $$;

-- La validation manuelle rattache, si possible, le transfert reçu correspondant.
create or replace function public.admin_valider_recharge(p_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare r public.recharge;
begin
  perform public.verifier_admin();
  select * into r from public.recharge where id = p_id for update;
  if not found then raise exception 'Recharge introuvable.'; end if;
  if r.statut <> 'en_attente' then raise exception 'Recharge déjà traitée.'; end if;
  update public.recharge set statut = 'valide', traite = now() where id = p_id;
  update public.profil set solde = solde + r.montant, maj = now() where user_id = r.user_id;
  update public.recu set recharge_id = p_id
   where recharge_id is null and operateur = r.operateur and reference = public.ref_norm(r.reference);
end $$;

-- Déclaration (automatique depuis le SMS, ou à la main) : validée tout de suite si le transfert est déjà reçu.
create or replace function public.declarer_recharge(p_operateur text, p_reference text, p_montant integer) returns json
language plpgsql security definer set search_path = public as $$
declare v_ref text := public.ref_norm(p_reference);
        v_min integer := public.param_int('recharge_min', 500);
        r public.recharge;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  if p_operateur not in ('OM', 'MOMO') then raise exception 'Opérateur inconnu.'; end if;
  if length(regexp_replace(v_ref, '[^A-Z0-9]', '', 'g')) < 6 then raise exception 'ID de transaction trop court : recopiez-le en entier.'; end if;
  if p_montant is null or p_montant < v_min then raise exception 'Montant minimum : % FCFA.', v_min; end if;
  if p_montant > 500000 then raise exception 'Montant trop élevé.'; end if;
  if (select count(*) from public.recharge where user_id = auth.uid() and statut = 'en_attente') >= 5 then
    raise exception 'Vous avez déjà 5 recharges en attente de validation.';
  end if;
  begin
    insert into public.recharge (user_id, operateur, reference, montant)
    values (auth.uid(), p_operateur, v_ref, p_montant) returning * into r;
  exception when unique_violation then
    raise exception 'Cet ID de transaction a déjà été déclaré.';
  end;
  perform public.rapprocher_recharges();
  select * into r from public.recharge where id = r.id;
  return json_build_object('id', r.id, 'montant', r.montant, 'statut', r.statut, 'auto', r.auto);
end $$;

-- Porte-monnaie : ajoute les codes du menu de paiement.
create or replace function public.mon_portefeuille() returns json
language plpgsql security definer set search_path = public as $$
declare p public.profil;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  insert into public.profil (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  select * into p from public.profil where user_id = auth.uid();
  return json_build_object(
    'solde', p.solde,
    'offerts_restants', greatest(0, public.param_int('exports_offerts', 1) - p.offerts_utilises),
    'suspendu', p.suspendu,
    'prix_petit', public.param_int('prix_export_petit', 500),
    'prix_grand', public.param_int('prix_export_grand', 1000),
    'seuil', public.param_int('seuil_export', 200),
    'recharge_min', public.param_int('recharge_min', 500),
    'numero_om', (select valeur from public.parametre where cle = 'numero_om'),
    'numero_momo', (select valeur from public.parametre where cle = 'numero_momo'),
    'nom_marchand', (select valeur from public.parametre where cle = 'nom_marchand'),
    'ussd_om', coalesce((select valeur from public.parametre where cle = 'ussd_om'), '#150#'),
    'ussd_momo', coalesce((select valeur from public.parametre where cle = 'ussd_momo'), '*126#'),
    'admin', public.est_admin(),
    'en_attente', (select coalesce(sum(montant), 0) from public.recharge where user_id = auth.uid() and statut = 'en_attente'),
    'historique', (select coalesce(json_agg(h order by h.quand desc), '[]'::json) from (
        (select 'recharge' as nature, r.montant, r.statut, r.motif, r.operateur as detail,
                (extract(epoch from r.cree) * 1000)::bigint as quand
           from public.recharge r where r.user_id = auth.uid() order by r.cree desc limit 15)
        union all
        (select 'export', -e.prix, case when e.offert then 'offert' else 'valide' end, e.periode, e.nb_operations::text,
                (extract(epoch from e.cree) * 1000)::bigint
           from public.export e where e.user_id = auth.uid() order by e.cree desc limit 15)) h),
    'serveur', (extract(epoch from now()) * 1000)::bigint);
end $$;

-- ---------- Suivi de l'argent ----------
-- saisie.source : la dépense a été payée avec cet argent (clé de l'opération source : « OM|ID… » ou « S|uid »).
--   Une saisie en mode « SUIVI » est une dépense hors caisse espèces (payée avec un retrait, un salaire…).
-- suivi : une opération existante (SMS ou espèces), rattachée à l'argent qui l'a payée. source NULL = détachée.
alter table public.saisie add column if not exists source text;

create table if not exists public.suivi (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  cle text not null,
  source text,
  caisse text,
  maj bigint not null default 0,
  primary key (user_id, cle)
);
create index if not exists suivi_caisse on public.suivi (user_id, caisse, maj);
alter table public.suivi enable row level security;
drop policy if exists proprietaire on public.suivi;
create policy proprietaire on public.suivi for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
revoke all on public.suivi from anon;
grant select, insert, update, delete on public.suivi to authenticated;

-- ---------- Dettes et créances (communes à tout le compte) ----------
-- Une ligne = un mouvement avec une personne (le « tiers ») :
--   type « CREANCE » (on me doit) ou « DETTE » (je dois) ; sens +1 = la somme due augmente (prêt accordé,
--   emprunt reçu), -1 = elle diminue (remboursement reçu ou versé).
--   cle : opération qui a fait bouger l'argent (« OM|ID… », « MOMO|ID… », « S|uid » pour une saisie) ; NULL pour
--   une dette ou une créance ancienne saisie à la main. echeance : date prévue de remboursement (ms), facultative.
--   Montants en centimes ; annule = 1 : supprimée (gardée pour la synchronisation).
create table if not exists public.engagement (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  tiers text,
  numero text,
  type text,
  sens integer,
  montant bigint,
  date bigint,
  echeance bigint,
  motif text,
  cle text,
  annule integer not null default 0,
  maj bigint not null default 0,
  primary key (user_id, id)
);
create index if not exists engagement_maj on public.engagement (user_id, maj);
alter table public.engagement enable row level security;
drop policy if exists proprietaire on public.engagement;
create policy proprietaire on public.engagement for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
revoke all on public.engagement from anon;
grant select, insert, update, delete on public.engagement to authenticated;

-- ---------- Droits des fonctions ----------
do $$
declare f text;
begin
  foreach f in array array['admin_parametre(text, text)', 'admin_signaler_recus(json)', 'admin_recus(integer)',
                           'admin_valider_recharge(bigint)', 'declarer_recharge(text, text, integer)', 'mon_portefeuille()'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to authenticated', f);
  end loop;
  foreach f in array array['rapprocher_recharges()', 'ref_norm(text)'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
  end loop;
end $$;
