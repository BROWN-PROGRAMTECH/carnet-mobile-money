-- Carnet Mobile Money · version 0.6 : porte-monnaie prépayé et paiement à l'export Excel.
-- À exécuter UNE fois dans Supabase → SQL Editor, après schema.sql. Peut être relancé sans danger.
--
-- Principe :
--  - L'application est gratuite. Seul l'export Excel est payant, à chaque fichier :
--      1 à 200 opérations : 500 FCFA ; plus de 200 : 1 000 FCFA (réglable). Le premier export est offert.
--  - Le client recharge son porte-monnaie par Orange Money ou MoMo, puis déclare l'ID de transaction.
--    Le superadmin valide la recharge une fois ; ensuite chaque export se débite instantanément.
--  - Hors ligne, l'application peut débiter sur le dernier solde connu ; le débit est transmis au retour du
--    réseau (le solde peut alors devenir négatif, ce qui bloque les exports suivants jusqu'à une recharge).
--  - Le superadmin ne voit jamais les transactions des clients.

-- ---------- Tables ----------

create table if not exists public.parametre (
  cle text primary key,
  valeur text not null default ''
);
insert into public.parametre (cle, valeur) values
  ('prix_export_petit', '500'),
  ('prix_export_grand', '1000'),
  ('seuil_export', '200'),
  ('exports_offerts', '1'),
  ('recharge_min', '500'),
  ('numero_om', ''),
  ('numero_momo', ''),
  ('nom_marchand', 'Carnet Mobile Money')
on conflict (cle) do nothing;

create table if not exists public.profil (
  user_id uuid primary key references auth.users (id) on delete cascade,
  telephone text,
  solde integer not null default 0,
  offerts_utilises integer not null default 0,
  suspendu boolean not null default false,
  note text not null default '',
  cree timestamptz not null default now(),
  maj timestamptz not null default now()
);

create table if not exists public.recharge (
  id bigserial primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  operateur text not null check (operateur in ('OM', 'MOMO', 'ADMIN')),
  reference text not null,
  montant integer not null check (montant between -1000000 and 1000000 and montant <> 0),
  statut text not null default 'en_attente' check (statut in ('en_attente', 'valide', 'refuse')),
  motif text not null default '',
  cree timestamptz not null default now(),
  traite timestamptz,
  unique (operateur, reference)
);
create index if not exists recharge_user on public.recharge (user_id, cree desc);
create index if not exists recharge_statut on public.recharge (statut);

create table if not exists public.export (
  id uuid primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  device_id text,
  nb_operations integer not null check (nb_operations >= 0),
  periode text not null default '',
  prix integer not null default 0,
  offert boolean not null default false,
  hors_ligne boolean not null default false,
  date_export timestamptz not null default now(),
  cree timestamptz not null default now()
);
create index if not exists export_user on public.export (user_id, cree desc);
create index if not exists export_cree on public.export (cree);

create table if not exists public.incident (
  id bigserial primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  device_id text,
  type text not null,
  message text,
  version_app text,
  cree timestamptz not null default now()
);
create index if not exists incident_cree on public.incident (cree);

create table if not exists public.admin (
  user_id uuid primary key references auth.users (id) on delete cascade
);

alter table public.appareil add column if not exists cree timestamptz not null default now();

-- ---------- Profil créé automatiquement à l'inscription ----------

create or replace function public.nouveau_compte() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profil (user_id, telephone)
  values (new.id, nullif(substring(coalesce(new.email, '') from '^237(\d{9})@'), ''))
  on conflict (user_id) do nothing;
  return new;
end $$;

drop trigger if exists nouveau_compte on auth.users;
create trigger nouveau_compte after insert on auth.users
  for each row execute function public.nouveau_compte();

-- Comptes déjà créés avant cette version.
insert into public.profil (user_id, telephone)
select u.id, nullif(substring(coalesce(u.email, '') from '^237(\d{9})@'), '') from auth.users u
on conflict (user_id) do nothing;

-- ---------- Fonctions utilitaires ----------

create or replace function public.est_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.admin where user_id = auth.uid());
$$;

create or replace function public.param_int(p_cle text, p_defaut integer) returns integer
language sql stable security definer set search_path = public as $$
  select coalesce((select nullif(regexp_replace(valeur, '\D', '', 'g'), '')::integer from public.parametre where cle = p_cle), p_defaut);
$$;

create or replace function public.prix_export(p_nb integer) returns integer
language sql stable security definer set search_path = public as $$
  select case when coalesce(p_nb, 0) <= 0 then 0
              when p_nb <= public.param_int('seuil_export', 200) then public.param_int('prix_export_petit', 500)
              else public.param_int('prix_export_grand', 1000) end;
$$;

-- ---------- Côté client ----------

-- Tout ce que l'application doit savoir : solde, tarifs, export offert, numéros de paiement.
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

create or replace function public.declarer_recharge(p_operateur text, p_reference text, p_montant integer) returns json
language plpgsql security definer set search_path = public as $$
declare v_ref text := upper(regexp_replace(coalesce(p_reference, ''), '\s', '', 'g'));
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
  return json_build_object('id', r.id, 'montant', r.montant, 'statut', r.statut);
end $$;

-- Débit d'un export. p_id est choisi par le téléphone : renvoyer le même export ne débite jamais deux fois.
-- p_hors_ligne = vrai : export déjà fait hors ligne, il est enregistré même si le solde devient négatif.
create or replace function public.payer_export(p_id uuid, p_nb integer, p_periode text, p_device text,
                                               p_date bigint default null, p_hors_ligne boolean default false) returns json
language plpgsql security definer set search_path = public as $$
declare p public.profil;
        e public.export;
        v_prix integer;
        v_offert boolean := false;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  if p_id is null or p_nb is null or p_nb < 0 then raise exception 'Demande d''export invalide.'; end if;
  insert into public.profil (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  select * into p from public.profil where user_id = auth.uid() for update;

  select * into e from public.export where id = p_id;
  if found then
    if e.user_id <> auth.uid() then raise exception 'Identifiant d''export déjà utilisé.'; end if;
    return json_build_object('ok', true, 'deja', true, 'prix', e.prix, 'offert', e.offert, 'solde', p.solde,
      'offerts_restants', greatest(0, public.param_int('exports_offerts', 1) - p.offerts_utilises));
  end if;

  if p.suspendu and not p_hors_ligne then raise exception 'Compte suspendu : contactez le service client.'; end if;

  v_prix := public.prix_export(p_nb);
  if v_prix > 0 and p.offerts_utilises < public.param_int('exports_offerts', 1) then
    v_offert := true;
    v_prix := 0;
  end if;
  if v_prix > 0 and p.solde < v_prix and not p_hors_ligne then
    raise exception 'Solde insuffisant : % FCFA disponibles, cet export coûte % FCFA. Rechargez votre porte-monnaie.', p.solde, v_prix;
  end if;

  update public.profil
     set solde = solde - v_prix, offerts_utilises = offerts_utilises + case when v_offert then 1 else 0 end, maj = now()
   where user_id = auth.uid()
   returning * into p;
  insert into public.export (id, user_id, device_id, nb_operations, periode, prix, offert, hors_ligne, date_export)
  values (p_id, auth.uid(), p_device, p_nb, left(coalesce(p_periode, ''), 120), v_prix, v_offert, p_hors_ligne,
          case when p_date is null then now() else to_timestamp(p_date / 1000.0) end);

  return json_build_object('ok', true, 'deja', false, 'prix', v_prix, 'offert', v_offert, 'solde', p.solde,
    'offerts_restants', greatest(0, public.param_int('exports_offerts', 1) - p.offerts_utilises));
end $$;

-- ---------- Côté superadmin (refusé à tout autre compte) ----------

create or replace function public.verifier_admin() returns void
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.est_admin() then raise exception 'Réservé au superadmin.'; end if;
end $$;

create or replace function public.admin_stats() returns json
language plpgsql stable security definer set search_path = public as $$
declare debut timestamptz := date_trunc('month', now());
begin
  perform public.verifier_admin();
  return json_build_object(
    'comptes', (select count(*) from public.profil),
    'nouveaux_7_jours', (select count(*) from public.profil where cree > now() - interval '7 days'),
    'clients_payants', (select count(distinct user_id) from public.export where prix > 0),
    'soldes_total', (select coalesce(sum(greatest(solde, 0)), 0) from public.profil),
    'soldes_negatifs', (select count(*) from public.profil where solde < 0),
    'recharges_en_attente', (select count(*) from public.recharge where statut = 'en_attente'),
    'montant_en_attente', (select coalesce(sum(montant), 0) from public.recharge where statut = 'en_attente'),
    'recharges_mois', (select coalesce(sum(montant), 0) from public.recharge where statut = 'valide' and operateur <> 'ADMIN' and traite >= debut),
    'exports_mois', (select count(*) from public.export where cree >= debut),
    'exports_payants_mois', (select count(*) from public.export where cree >= debut and prix > 0),
    'revenu_exports_mois', (select coalesce(sum(prix), 0) from public.export where cree >= debut),
    'appareils', (select count(*) from public.appareil),
    'appareils_silencieux', (select count(*) from public.appareil
                             where dernier_sync < (extract(epoch from now() - interval '24 hours') * 1000)::bigint),
    'incidents_7_jours', (select count(*) from public.incident where cree > now() - interval '7 days'));
end $$;

create or replace function public.admin_comptes() returns table (
  user_id uuid, telephone text, solde integer, offerts_utilises integer, suspendu boolean, note text, cree timestamptz,
  exports bigint, depense bigint, dernier_export timestamptz, recharges_en_attente bigint, appareils bigint, dernier_sync bigint)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return query
    select p.user_id, p.telephone, p.solde, p.offerts_utilises, p.suspendu, p.note, p.cree,
           (select count(*) from public.export e where e.user_id = p.user_id),
           (select coalesce(sum(e.prix), 0) from public.export e where e.user_id = p.user_id),
           (select max(e.cree) from public.export e where e.user_id = p.user_id),
           (select count(*) from public.recharge r where r.user_id = p.user_id and r.statut = 'en_attente'),
           (select count(*) from public.appareil d where d.user_id = p.user_id),
           (select max(d.dernier_sync) from public.appareil d where d.user_id = p.user_id)
    from public.profil p
    order by p.cree desc;
end $$;

create or replace function public.admin_recharges(p_statut text default null) returns table (
  id bigint, user_id uuid, telephone text, operateur text, reference text, montant integer,
  statut text, motif text, cree timestamptz, traite timestamptz, solde integer)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return query
    select r.id, r.user_id, p.telephone, r.operateur, r.reference, r.montant, r.statut, r.motif, r.cree, r.traite, p.solde
    from public.recharge r left join public.profil p on p.user_id = r.user_id
    where p_statut is null or r.statut = p_statut
    order by r.cree desc
    limit 500;
end $$;

create or replace function public.admin_exports(p_jours integer default 30) returns table (
  id uuid, user_id uuid, telephone text, nb_operations integer, periode text, prix integer, offert boolean,
  hors_ligne boolean, date_export timestamptz, cree timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return query
    select e.id, e.user_id, p.telephone, e.nb_operations, e.periode, e.prix, e.offert, e.hors_ligne, e.date_export, e.cree
    from public.export e left join public.profil p on p.user_id = e.user_id
    where e.cree > now() - make_interval(days => greatest(1, p_jours))
    order by e.cree desc
    limit 1000;
end $$;

create or replace function public.admin_appareils() returns table (
  user_id uuid, telephone text, device_id text, modele text, android text, version_app text, dernier_sync bigint, cree timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return query
    select d.user_id, p.telephone, d.device_id, d.modele, d.android, d.version_app, d.dernier_sync, d.cree
    from public.appareil d left join public.profil p on p.user_id = d.user_id
    order by d.dernier_sync desc nulls last;
end $$;

create or replace function public.admin_incidents(p_jours integer default 30) returns table (
  id bigint, user_id uuid, telephone text, device_id text, type text, message text, version_app text, cree timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return query
    select i.id, i.user_id, p.telephone, i.device_id, i.type, i.message, i.version_app, i.cree
    from public.incident i left join public.profil p on p.user_id = i.user_id
    where i.cree > now() - make_interval(days => greatest(1, p_jours))
    order by i.cree desc
    limit 1000;
end $$;

-- Valider une recharge : le montant est ajouté au solde, une seule fois.
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
end $$;

create or replace function public.admin_refuser_recharge(p_id bigint, p_motif text default '') returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  update public.recharge set statut = 'refuse', motif = coalesce(p_motif, ''), traite = now()
   where id = p_id and statut = 'en_attente';
  if not found then raise exception 'Recharge introuvable ou déjà traitée.'; end if;
end $$;

-- Crédit (ou correction négative) décidé par le superadmin : geste commercial, remboursement…
create or replace function public.admin_crediter(p_user uuid, p_montant integer, p_motif text) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  if p_montant is null or p_montant = 0 then raise exception 'Montant invalide.'; end if;
  insert into public.recharge (user_id, operateur, reference, montant, statut, motif, traite)
  values (p_user, 'ADMIN', 'ADMIN-' || gen_random_uuid()::text, p_montant, 'valide', coalesce(p_motif, ''), now());
  update public.profil set solde = solde + p_montant, maj = now() where user_id = p_user;
  if not found then raise exception 'Compte introuvable.'; end if;
end $$;

create or replace function public.admin_modifier_compte(p_user uuid, p_suspendu boolean, p_note text, p_offerts integer default null)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  update public.profil
     set suspendu = coalesce(p_suspendu, suspendu), note = coalesce(p_note, note),
         offerts_utilises = coalesce(p_offerts, offerts_utilises), maj = now()
   where user_id = p_user;
  if not found then raise exception 'Compte introuvable.'; end if;
end $$;

create or replace function public.admin_parametre(p_cle text, p_valeur text) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  if p_cle not in ('prix_export_petit', 'prix_export_grand', 'seuil_export', 'exports_offerts', 'recharge_min',
                   'numero_om', 'numero_momo', 'nom_marchand') then
    raise exception 'Paramètre inconnu.';
  end if;
  insert into public.parametre (cle, valeur) values (p_cle, coalesce(p_valeur, ''))
  on conflict (cle) do update set valeur = excluded.valeur;
end $$;

-- ---------- Sécurité par ligne ----------

alter table public.parametre enable row level security;
alter table public.profil enable row level security;
alter table public.recharge enable row level security;
alter table public.export enable row level security;
alter table public.incident enable row level security;
alter table public.admin enable row level security;

drop policy if exists lecture on public.parametre;
create policy lecture on public.parametre for select to authenticated using (true);
drop policy if exists proprietaire on public.profil;
create policy proprietaire on public.profil for select to authenticated using (user_id = (select auth.uid()) or (select public.est_admin()));
drop policy if exists proprietaire on public.recharge;
create policy proprietaire on public.recharge for select to authenticated using (user_id = (select auth.uid()) or (select public.est_admin()));
drop policy if exists proprietaire on public.export;
create policy proprietaire on public.export for select to authenticated using (user_id = (select auth.uid()) or (select public.est_admin()));
drop policy if exists signaler on public.incident;
create policy signaler on public.incident for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists admin_lecture on public.incident;
create policy admin_lecture on public.incident for select to authenticated using ((select public.est_admin()));
drop policy if exists admin_lecture on public.admin;
create policy admin_lecture on public.admin for select to authenticated using ((select public.est_admin()));
drop policy if exists admin_lecture on public.appareil;
create policy admin_lecture on public.appareil for select to authenticated using ((select public.est_admin()));

-- Toute modification du solde passe par les fonctions ci-dessus : aucune écriture directe pour les clients.
revoke all on public.parametre, public.profil, public.recharge, public.export, public.incident, public.admin from anon, authenticated;
grant select on public.parametre, public.profil, public.recharge, public.export, public.admin to authenticated;
grant select, insert on public.incident to authenticated;
grant usage, select on sequence public.incident_id_seq to authenticated;

do $$
declare f text;
begin
  foreach f in array array[
    'nouveau_compte()', 'est_admin()', 'param_int(text, integer)', 'prix_export(integer)',
    'mon_portefeuille()', 'declarer_recharge(text, text, integer)',
    'payer_export(uuid, integer, text, text, bigint, boolean)', 'verifier_admin()',
    'admin_stats()', 'admin_comptes()', 'admin_recharges(text)', 'admin_exports(integer)', 'admin_appareils()',
    'admin_incidents(integer)', 'admin_valider_recharge(bigint)', 'admin_refuser_recharge(bigint, text)',
    'admin_crediter(uuid, integer, text)', 'admin_modifier_compte(uuid, boolean, text, integer)',
    'admin_parametre(text, text)'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    if f <> 'nouveau_compte()' then execute format('grant execute on function public.%s to authenticated', f); end if;
  end loop;
end $$;

-- ---------- Devenir superadmin (une seule fois) ----------
-- Créez d'abord votre compte dans l'application, puis exécutez (en remplaçant par votre numéro) :
--   insert into public.admin (user_id) select id from auth.users where email = '2376XXXXXXXX@carnet-mm.app';
