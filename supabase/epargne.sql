-- Carnet Mobile Money · version 0.17 : épargne et tontines (gratuites, dans la base).
-- À exécuter UNE fois dans Supabase → SQL Editor, après analyse.sql. Peut être relancé sans danger.
-- Sauvegarde des objectifs d'épargne, des tontines et de leurs versements, communs au compte (tous les téléphones).
-- Montants en centimes ; annule = 1 : supprimé (gardé pour la synchronisation) ; membres : un nom par ligne.

create table if not exists public.projet_epargne (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  type text,
  nom text,
  montant bigint,
  frequence text,
  debut bigint,
  fin bigint,
  membres text,
  moi integer,
  annule integer not null default 0,
  maj bigint not null default 0,
  primary key (user_id, id)
);
create index if not exists projet_epargne_maj on public.projet_epargne (user_id, maj);

create table if not exists public.versement_epargne (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  projet text,
  date bigint,
  montant bigint,
  sens integer,
  membre integer,
  seance integer,
  cle text,
  motif text,
  annule integer not null default 0,
  maj bigint not null default 0,
  primary key (user_id, id)
);
create index if not exists versement_epargne_maj on public.versement_epargne (user_id, maj);

do $$
declare t text;
begin
  foreach t in array array['projet_epargne', 'versement_epargne'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists proprietaire on public.%I', t);
    execute format('create policy proprietaire on public.%I for all to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t);
    execute format('revoke all on public.%I from anon', t);
    execute format('grant select, insert, update, delete on public.%I to authenticated', t);
  end loop;
end $$;
