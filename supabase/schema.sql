-- Carnet Mobile Money : base de sauvegarde en ligne (version 0.4)
-- À exécuter une seule fois dans Supabase : SQL Editor → New query → coller tout ce fichier → Run.
-- Chaque utilisateur ne voit et ne modifie que ses propres lignes (règles d'accès par ligne, RLS).
-- Les montants sont en centimes de FCFA ; les dates (date, maj, cloture_at…) en millisecondes depuis 1970.

create table if not exists public.sms (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  hash text not null,
  address text,
  body text,
  date bigint,
  sub text,
  maj bigint not null default 0,
  primary key (user_id, hash)
);

create table if not exists public.classement (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  cle text not null,
  categorie text,
  motif text,
  maj bigint not null default 0,
  primary key (user_id, cle)
);

create table if not exists public.regle (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  numero text not null,
  categorie text,
  maj bigint not null default 0,
  primary key (user_id, numero)
);

create table if not exists public.saisie (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  uid text not null,
  date bigint,
  mode text,
  sens integer,
  montant bigint,
  libelle text,
  ref text,
  annule integer default 0,
  depuis_sms integer default 0,
  cree bigint,
  categorie text,
  maj bigint not null default 0,
  primary key (user_id, uid)
);

create table if not exists public.jour (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  jour text not null,
  fond bigint,
  compte bigint,
  note text,
  cloture_at bigint,
  maj bigint not null default 0,
  primary key (user_id, jour)
);

-- Parc d'appareils : sert au futur tableau de bord superadmin (version, modèle, dernière sauvegarde).
create table if not exists public.appareil (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  device_id text not null,
  modele text,
  android text,
  version_app text,
  dernier_sync bigint,
  primary key (user_id, device_id)
);

create index if not exists sms_maj on public.sms (user_id, maj);
create index if not exists classement_maj on public.classement (user_id, maj);
create index if not exists regle_maj on public.regle (user_id, maj);
create index if not exists saisie_maj on public.saisie (user_id, maj);
create index if not exists jour_maj on public.jour (user_id, maj);

-- Sécurité : accès réservé au propriétaire de chaque ligne, uniquement pour les utilisateurs connectés.
do $$
declare t text;
begin
  foreach t in array array['sms', 'classement', 'regle', 'saisie', 'jour', 'appareil'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists proprietaire on public.%I', t);
    execute format('create policy proprietaire on public.%I for all to authenticated '
                   'using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t);
    execute format('revoke all on public.%I from anon', t);
    execute format('grant select, insert, update, delete on public.%I to authenticated', t);
  end loop;
end $$;
