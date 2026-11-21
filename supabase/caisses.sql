-- Carnet Mobile Money · version 0.8 : un compte, plusieurs téléphones, une caisse par téléphone.
-- À exécuter UNE fois dans Supabase → SQL Editor, après schema.sql et portefeuille.sql. Peut être relancé sans danger.
--
-- Principe :
--  - Chaque téléphone travaille sur sa caisse : ses SMS, classements, espèces et journées portent l'identifiant
--    de la caisse, et le téléphone ne reçoit que les données de sa caisse.
--  - Les règles « toujours classer ce contact » et le porte-monnaie restent communs au compte.
--  - Chaque téléphone envoie un résumé par jour (ventes, achats, charges, frais, résultat, soldes) : la vue
--    « Toutes mes caisses » et l'export consolidé se font à partir de ces résumés, sans échanger les SMS.
--  - Un code patron (4 à 6 chiffres, haché, 5 essais puis 15 minutes de blocage) protège la vue de toutes
--    les caisses et la reprise de la caisse d'un autre téléphone. C'est une protection de l'application :
--    toute personne qui connaît le numéro et le code secret du compte reste propriétaire des données.

-- ---------- Tables ----------

create table if not exists public.caisse (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  nom text not null,
  device_id text,
  modele text,
  cree timestamptz not null default now(),
  maj timestamptz not null default now()
);
create index if not exists caisse_user on public.caisse (user_id);

alter table public.sms add column if not exists caisse text;
alter table public.classement add column if not exists caisse text;
alter table public.saisie add column if not exists caisse text;
alter table public.jour add column if not exists caisse text;
create index if not exists sms_caisse on public.sms (user_id, caisse, maj);
create index if not exists classement_caisse on public.classement (user_id, caisse, maj);
create index if not exists saisie_caisse on public.saisie (user_id, caisse, maj);
create index if not exists jour_caisse on public.jour (user_id, caisse, maj);

-- Résumé d'une journée d'une caisse. Montants en centimes de FCFA, comme dans l'application.
create table if not exists public.resume (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  caisse text not null,
  jour text not null,
  nb_operations integer not null default 0,
  a_classer integer not null default 0,
  ventes bigint not null default 0,
  achats bigint not null default 0,
  charges bigint not null default 0,
  frais bigint not null default 0,
  resultat bigint not null default 0,
  apports bigint not null default 0,
  retraits bigint not null default 0,
  solde_om bigint,
  solde_momo bigint,
  especes_compte bigint,
  ecart_om bigint,
  ecart_momo bigint,
  ecart_especes bigint,
  maj bigint not null default 0,
  primary key (user_id, caisse, jour)
);

-- Code patron (haché) et limitation des essais. Ces colonnes ne sont jamais lisibles par l'application.
alter table public.profil add column if not exists code_patron text;
alter table public.profil add column if not exists essais_patron integer not null default 0;
alter table public.profil add column if not exists bloque_patron timestamptz;

-- ---------- Code patron ----------

create or replace function public.hash_patron(p_user uuid, p_code text) returns text
language sql immutable as $$
  select encode(sha256(convert_to(p_user::text || ':carnet:' || coalesce(p_code, ''), 'UTF8')), 'hex');
$$;

-- Renvoie null si le code est bon (ou si aucun code n'est défini), sinon le message d'erreur.
-- Ne lève pas d'exception, pour que le compteur d'essais soit bien enregistré.
create or replace function public.controle_patron(p_code text) returns text
language plpgsql security definer set search_path = public as $$
declare p public.profil;
begin
  insert into public.profil (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  select * into p from public.profil where user_id = auth.uid() for update;
  if p.code_patron is null then return null; end if;
  if p.bloque_patron is not null and p.bloque_patron > now() then
    return 'Trop d''essais : réessayez dans ' || greatest(1, ceil(extract(epoch from p.bloque_patron - now()) / 60))::int || ' minute(s).';
  end if;
  if public.hash_patron(auth.uid(), p_code) = p.code_patron then
    update public.profil set essais_patron = 0, bloque_patron = null where user_id = auth.uid();
    return null;
  end if;
  if p.essais_patron + 1 >= 5 then
    update public.profil set essais_patron = 0, bloque_patron = now() + interval '15 minutes' where user_id = auth.uid();
    return 'Code patron incorrect. Trop d''essais : bloqué 15 minutes.';
  end if;
  update public.profil set essais_patron = p.essais_patron + 1 where user_id = auth.uid();
  return 'Code patron incorrect (' || (4 - p.essais_patron) || ' essai(s) restant(s)).';
end $$;

create or replace function public.definir_code_patron(p_ancien text, p_nouveau text) returns json
language plpgsql security definer set search_path = public as $$
declare v_err text;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  if coalesce(p_nouveau, '') !~ '^\d{4,6}$' then return json_build_object('ok', false, 'erreur', 'Le code patron doit faire 4 à 6 chiffres.'); end if;
  v_err := public.controle_patron(p_ancien);
  if v_err is not null then return json_build_object('ok', false, 'erreur', v_err); end if;
  update public.profil set code_patron = public.hash_patron(auth.uid(), p_nouveau), essais_patron = 0, bloque_patron = null, maj = now()
   where user_id = auth.uid();
  return json_build_object('ok', true);
end $$;

create or replace function public.verifier_code_patron(p_code text) returns json
language plpgsql security definer set search_path = public as $$
declare v_err text;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  v_err := public.controle_patron(p_code);
  return json_build_object('ok', v_err is null, 'erreur', v_err);
end $$;

-- ---------- Caisses ----------

create or replace function public.mes_caisses() returns json
language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  return json_build_object(
    'code_defini', coalesce((select code_patron is not null from public.profil where user_id = auth.uid()), false),
    'caisses', (select coalesce(json_agg(json_build_object(
        'id', c.id, 'nom', c.nom, 'device_id', c.device_id, 'modele', c.modele,
        'cree', (extract(epoch from c.cree) * 1000)::bigint,
        'activite', (select max(r.maj) from public.resume r where r.user_id = c.user_id and r.caisse = c.id::text))
      order by c.cree), '[]'::json)
      from public.caisse c where c.user_id = auth.uid()));
end $$;

create or replace function public.creer_caisse(p_nom text, p_device text, p_modele text) returns json
language plpgsql security definer set search_path = public as $$
declare v_nom text := left(btrim(coalesce(p_nom, '')), 40);
        c public.caisse;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  if v_nom = '' then raise exception 'Donnez un nom à la caisse.'; end if;
  if (select count(*) from public.caisse where user_id = auth.uid()) >= 20 then raise exception 'Maximum 20 caisses par compte.'; end if;
  if exists (select 1 from public.caisse where user_id = auth.uid() and lower(nom) = lower(v_nom)) then
    raise exception 'Une caisse porte déjà ce nom.';
  end if;
  insert into public.caisse (user_id, nom, device_id, modele) values (auth.uid(), v_nom, p_device, p_modele) returning * into c;
  return json_build_object('id', c.id, 'nom', c.nom);
end $$;

create or replace function public.reprendre_caisse(p_caisse uuid, p_code text, p_device text, p_modele text) returns json
language plpgsql security definer set search_path = public as $$
declare v_err text;
        c public.caisse;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  select * into c from public.caisse where id = p_caisse and user_id = auth.uid();
  if not found then return json_build_object('ok', false, 'erreur', 'Caisse introuvable.'); end if;
  v_err := public.controle_patron(p_code);
  if v_err is not null then return json_build_object('ok', false, 'erreur', v_err); end if;
  update public.caisse set device_id = p_device, modele = p_modele, maj = now() where id = p_caisse;
  return json_build_object('ok', true, 'id', c.id, 'nom', c.nom);
end $$;

create or replace function public.renommer_caisse(p_caisse uuid, p_nom text) returns void
language plpgsql security definer set search_path = public as $$
declare v_nom text := left(btrim(coalesce(p_nom, '')), 40);
begin
  if v_nom = '' then raise exception 'Donnez un nom à la caisse.'; end if;
  if exists (select 1 from public.caisse where user_id = auth.uid() and lower(nom) = lower(v_nom) and id <> p_caisse) then
    raise exception 'Une caisse porte déjà ce nom.';
  end if;
  update public.caisse set nom = v_nom, maj = now() where id = p_caisse and user_id = auth.uid();
  if not found then raise exception 'Caisse introuvable.'; end if;
end $$;

-- Vue « Toutes mes caisses » sur une période (jours au format AAAA-MM-JJ, bornes incluses).
create or replace function public.vue_caisses(p_code text, p_debut text, p_fin text) returns json
language plpgsql security definer set search_path = public as $$
declare v_err text;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  v_err := public.controle_patron(p_code);
  if v_err is not null then return json_build_object('ok', false, 'erreur', v_err); end if;
  return json_build_object('ok', true,
    'caisses', (select coalesce(json_agg(x order by x.nom), '[]'::json) from (
      select c.id, c.nom, c.modele,
             coalesce(sum(r.nb_operations), 0) as nb_operations, coalesce(sum(r.a_classer), 0) as a_classer,
             coalesce(sum(r.ventes), 0) as ventes, coalesce(sum(r.achats), 0) as achats, coalesce(sum(r.charges), 0) as charges,
             coalesce(sum(r.frais), 0) as frais, coalesce(sum(r.resultat), 0) as resultat,
             coalesce(sum(r.apports), 0) as apports, coalesce(sum(r.retraits), 0) as retraits,
             (select r2.solde_om from public.resume r2 where r2.user_id = c.user_id and r2.caisse = c.id::text
                and r2.jour <= p_fin and r2.solde_om is not null order by r2.jour desc limit 1) as solde_om,
             (select r2.solde_momo from public.resume r2 where r2.user_id = c.user_id and r2.caisse = c.id::text
                and r2.jour <= p_fin and r2.solde_momo is not null order by r2.jour desc limit 1) as solde_momo,
             (select max(r3.maj) from public.resume r3 where r3.user_id = c.user_id and r3.caisse = c.id::text) as activite
      from public.caisse c
      left join public.resume r on r.user_id = c.user_id and r.caisse = c.id::text and r.jour between p_debut and p_fin
      where c.user_id = auth.uid()
      group by c.id, c.nom, c.modele, c.user_id) x),
    'jours', (select coalesce(json_agg(json_build_object('caisse', c.nom, 'jour', r.jour, 'nb_operations', r.nb_operations,
        'a_classer', r.a_classer, 'ventes', r.ventes, 'achats', r.achats, 'charges', r.charges, 'frais', r.frais,
        'resultat', r.resultat, 'apports', r.apports, 'retraits', r.retraits, 'solde_om', r.solde_om, 'solde_momo', r.solde_momo,
        'especes_compte', r.especes_compte) order by r.jour, c.nom), '[]'::json)
      from public.resume r join public.caisse c on c.id::text = r.caisse and c.user_id = r.user_id
      where r.user_id = auth.uid() and r.jour between p_debut and p_fin));
end $$;

-- Superadmin : nombre de caisses par compte (aucun montant de caisse n'est exposé).
create or replace function public.admin_caisses() returns table (user_id uuid, caisses bigint)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return query select c.user_id, count(*) from public.caisse c group by c.user_id;
end $$;

-- ---------- Sécurité par ligne ----------

alter table public.caisse enable row level security;
alter table public.resume enable row level security;
drop policy if exists proprietaire on public.caisse;
create policy proprietaire on public.caisse for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists proprietaire on public.resume;
create policy proprietaire on public.resume for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

revoke all on public.caisse, public.resume from anon, authenticated;
grant select on public.caisse to authenticated;
grant select, insert, update, delete on public.resume to authenticated;
-- Le hachage du code patron ne doit pas être lisible : l'application passe uniquement par les fonctions.
revoke select on public.profil from authenticated;

do $$
declare f text;
begin
  foreach f in array array[
    'hash_patron(uuid, text)', 'controle_patron(text)', 'definir_code_patron(text, text)', 'verifier_code_patron(text)',
    'mes_caisses()', 'creer_caisse(text, text, text)', 'reprendre_caisse(uuid, text, text, text)',
    'renommer_caisse(uuid, text)', 'vue_caisses(text, text, text)', 'admin_caisses()'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    if f not in ('hash_patron(uuid, text)', 'controle_patron(text)') then
      execute format('grant execute on function public.%s to authenticated', f);
    end if;
  end loop;
end $$;
