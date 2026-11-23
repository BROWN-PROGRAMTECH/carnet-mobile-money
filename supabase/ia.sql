-- Carnet Mobile Money · version 0.13 : agent IA.
-- À exécuter UNE fois dans Supabase → SQL Editor, après alertes.sql. Peut être relancé sans danger.
--
--  - Lecture des SMS inconnus (gratuite pour tous, 100 par mois et par compte) : le téléphone envoie le SMS masqué
--    (numéros, noms, ID remplacés) à la fonction Edge « ia », qui demande à Claude (Anthropic) une règle de lecture,
--    la vérifie sur l'exemple et l'enregistre ici « proposée ». Elle sert tout de suite au compte qui l'a demandée ;
--    validée par le superadmin, elle sert à tous les téléphones.
--  - Application « Assistant IA » (500 FCFA/mois, 300 questions par mois) : classement automatique des contacts
--    nouveaux et questions en français sur ses chiffres (totaux seulement, personnes masquées).

insert into public.module (code, nom, description, prix, ordre) values
  ('ASSISTANT', 'Assistant IA', 'Classement automatique de vos opérations et réponses à vos questions sur vos chiffres (« combien j''ai vendu cette semaine ? »). 300 questions par mois.', 500, 70)
on conflict (code) do nothing;
update public.module set contenu = array_append(contenu, 'ASSISTANT')
 where code = 'PACK_ENTREPRISE' and not ('ASSISTANT' = any (contenu));
insert into public.parametre (cle, valeur) values ('ia_quota', '300'), ('ia_sms_mois', '100') on conflict (cle) do nothing;

create table if not exists public.ia_usage (
  user_id uuid not null references auth.users (id) on delete cascade,
  mois text not null,
  questions integer not null default 0,
  sms integer not null default 0,
  primary key (user_id, mois)
);

create table if not exists public.regle_ia (
  id uuid primary key default gen_random_uuid(),
  operateur text,
  signature text not null,
  montant text not null default '',
  solde text not null default '',
  frais text not null default '',
  sens integer not null default 0 check (sens in (-1, 0, 1)),
  libelle text not null default '',
  exemple text not null default '',
  explication text not null default '',
  statut text not null default 'proposee' check (statut in ('proposee', 'validee', 'refusee')),
  user_id uuid references auth.users (id) on delete set null,
  cree timestamptz not null default now(),
  traite timestamptz
);
create index if not exists regle_ia_statut on public.regle_ia (statut, cree);
create index if not exists regle_ia_user on public.regle_ia (user_id);
create unique index if not exists regle_ia_unique on public.regle_ia (coalesce(operateur, ''), signature);

alter table public.ia_usage enable row level security;
alter table public.regle_ia enable row level security;
revoke all on public.ia_usage, public.regle_ia from anon, authenticated;

-- Règles servies au téléphone : validées pour tous + proposées par ce compte (pas les refusées).
create or replace function public.regles_ia() returns json
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  return (select coalesce(json_agg(json_build_object('id', r.id, 'operateur', r.operateur, 'signature', r.signature, 'montant', r.montant,
            'solde', r.solde, 'frais', r.frais, 'sens', r.sens, 'libelle', r.libelle, 'validee', r.statut = 'validee') order by r.cree), '[]'::json)
          from public.regle_ia r where r.statut = 'validee' or (r.statut = 'proposee' and r.user_id = auth.uid()));
end $$;

create or replace function public.mon_ia() returns json
language plpgsql security definer set search_path = public as $$
declare v_mois text := to_char(now(), 'YYYY-MM'); u public.ia_usage;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  select * into u from public.ia_usage where user_id = auth.uid() and mois = v_mois;
  return json_build_object('questions', coalesce(u.questions, 0), 'quota', public.param_int('ia_quota', 300),
    'sms', coalesce(u.sms, 0), 'sms_quota', public.param_int('ia_sms_mois', 100),
    'actif', 'ASSISTANT' = any (public.modules_actifs(auth.uid())));
end $$;

-- Réservée à la fonction Edge : compte un appel (question ou SMS) et refuse au-delà du quota.
create or replace function public.consommer_ia(p_user uuid, p_nature text) returns json
language plpgsql security definer set search_path = public as $$
declare v_mois text := to_char(now(), 'YYYY-MM'); u public.ia_usage;
begin
  if p_nature not in ('question', 'sms') then raise exception 'Nature inconnue.'; end if;
  if p_nature = 'question' and not ('ASSISTANT' = any (public.modules_actifs(p_user))) then
    raise exception 'Activez l''application Assistant IA (Compte → Mes applications).';
  end if;
  insert into public.ia_usage (user_id, mois) values (p_user, v_mois) on conflict do nothing;
  select * into u from public.ia_usage where user_id = p_user and mois = v_mois for update;
  if p_nature = 'question' and u.questions >= public.param_int('ia_quota', 300) then
    raise exception 'Vous avez utilisé vos % questions du mois. Le compteur repart le 1er.', public.param_int('ia_quota', 300);
  end if;
  if p_nature = 'sms' and u.sms >= public.param_int('ia_sms_mois', 100) then
    raise exception 'Limite de lecture de SMS par l''IA atteinte pour ce mois.';
  end if;
  update public.ia_usage set questions = questions + (p_nature = 'question')::int, sms = sms + (p_nature = 'sms')::int
   where user_id = p_user and mois = v_mois returning * into u;
  return json_build_object('questions', u.questions, 'quota', public.param_int('ia_quota', 300));
end $$;

-- Réservée à la fonction Edge : enregistre une règle proposée (ou renvoie celle qui existe déjà).
create or replace function public.enregistrer_regle_ia(p_user uuid, p_regle json) returns json
language plpgsql security definer set search_path = public as $$
declare r public.regle_ia; v_op text := nullif(p_regle->>'operateur', '');
begin
  select * into r from public.regle_ia where coalesce(operateur, '') = coalesce(v_op, '') and signature = p_regle->>'signature';
  if not found then
    insert into public.regle_ia (operateur, signature, montant, solde, frais, sens, libelle, exemple, explication, user_id)
    values (v_op, left(p_regle->>'signature', 300), left(coalesce(p_regle->>'montant', ''), 300), left(coalesce(p_regle->>'solde', ''), 300),
            left(coalesce(p_regle->>'frais', ''), 300), coalesce((p_regle->>'sens')::int, 0), left(coalesce(p_regle->>'libelle', ''), 60),
            left(coalesce(p_regle->>'exemple', ''), 1000), left(coalesce(p_regle->>'explication', ''), 500), p_user)
    returning * into r;
  end if;
  return json_build_object('id', r.id, 'operateur', r.operateur, 'signature', r.signature, 'montant', r.montant, 'solde', r.solde,
    'frais', r.frais, 'sens', r.sens, 'libelle', r.libelle, 'validee', r.statut = 'validee', 'statut', r.statut);
end $$;

-- Superadmin : règles proposées à valider, et consommation du mois.
create or replace function public.admin_ia() returns json
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return json_build_object(
    'regles', (select coalesce(json_agg(json_build_object('id', r.id, 'operateur', r.operateur, 'signature', r.signature, 'montant', r.montant,
        'solde', r.solde, 'frais', r.frais, 'sens', r.sens, 'libelle', r.libelle, 'exemple', r.exemple, 'explication', r.explication,
        'statut', r.statut, 'cree', r.cree) order by (r.statut = 'proposee') desc, r.cree desc), '[]'::json)
      from (select * from public.regle_ia order by cree desc limit 200) r),
    'mois', (select json_build_object('questions', coalesce(sum(questions), 0), 'sms', coalesce(sum(sms), 0), 'comptes', count(*))
               from public.ia_usage where mois = to_char(now(), 'YYYY-MM')));
end $$;

create or replace function public.admin_regle_ia(p_id uuid, p_statut text) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  if p_statut not in ('validee', 'refusee', 'proposee') then raise exception 'Statut inconnu.'; end if;
  update public.regle_ia set statut = p_statut, traite = now() where id = p_id;
  if not found then raise exception 'Règle introuvable.'; end if;
end $$;

do $$
declare f text;
begin
  foreach f in array array['regles_ia()', 'mon_ia()', 'admin_ia()', 'admin_regle_ia(uuid, text)'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to authenticated', f);
  end loop;
  foreach f in array array['consommer_ia(uuid, text)', 'enregistrer_regle_ia(uuid, json)'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to service_role', f);
  end loop;
end $$;
