-- Carnet Mobile Money · version 0.9.1 : catégories créées par l'utilisateur, communes à tout le compte.
-- À exécuter UNE fois dans Supabase → SQL Editor, après finances.sql. Peut être relancé sans danger.
--
-- code : « RECETTE_P… » (entrée, résultat +), « CHARGE_P… » (sortie rangée sous Charges, résultat −, budget possible),
--        « P_… » (hors résultat), ou le code d'une catégorie fournie (ex. « VENTE ») pour la masquer.
-- sens : pour « P_… », +1 entrées, -1 sorties, 0 les deux. masquee : 1 = cachée dans les choix (l'historique reste).

create table if not exists public.categorie_perso (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  code text not null,
  nom text,
  sens integer not null default 0,
  masquee integer not null default 0,
  maj bigint not null default 0,
  primary key (user_id, code)
);
create index if not exists categorie_perso_maj on public.categorie_perso (user_id, maj);

alter table public.categorie_perso enable row level security;
drop policy if exists proprietaire on public.categorie_perso;
create policy proprietaire on public.categorie_perso for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
revoke all on public.categorie_perso from anon;
grant select, insert, update, delete on public.categorie_perso to authenticated;
