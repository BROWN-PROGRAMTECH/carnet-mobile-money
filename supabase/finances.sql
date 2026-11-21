-- Carnet Mobile Money · version 0.9 : gestion des finances (budgets, autres avoirs, trésorerie).
-- À exécuter UNE fois dans Supabase → SQL Editor, après caisses.sql. Peut être relancé sans danger.

-- Budget mensuel par poste de dépense, par caisse. Clé « caisse|poste » ; montants en centimes ; montant NULL = retiré.
create table if not exists public.budget (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  poste text not null,
  montant bigint,
  caisse text,
  maj bigint not null default 0,
  primary key (user_id, poste)
);
create index if not exists budget_caisse on public.budget (user_id, caisse, maj);

-- Autres avoirs saisis à la main (banque sans SMS, épargne, tontine…), par caisse. Clé « caisse|identifiant ».
create table if not exists public.avoir (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  nom text,
  montant bigint,
  caisse text,
  maj bigint not null default 0,
  primary key (user_id, id)
);
create index if not exists avoir_caisse on public.avoir (user_id, caisse, maj);

-- Trésorerie de fin de journée (OM + MoMo + banques lues par SMS + espèces), en centimes.
alter table public.resume add column if not exists tresorerie bigint;

alter table public.budget enable row level security;
alter table public.avoir enable row level security;
drop policy if exists proprietaire on public.budget;
create policy proprietaire on public.budget for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists proprietaire on public.avoir;
create policy proprietaire on public.avoir for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
revoke all on public.budget, public.avoir from anon;
grant select, insert, update, delete on public.budget, public.avoir to authenticated;

-- Vue « Toutes mes caisses » : ajoute la dernière trésorerie connue de chaque caisse.
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
             (select r2.tresorerie from public.resume r2 where r2.user_id = c.user_id and r2.caisse = c.id::text
                and r2.jour <= p_fin and r2.tresorerie is not null order by r2.jour desc limit 1) as tresorerie,
             (select max(r3.maj) from public.resume r3 where r3.user_id = c.user_id and r3.caisse = c.id::text) as activite
      from public.caisse c
      left join public.resume r on r.user_id = c.user_id and r.caisse = c.id::text and r.jour between p_debut and p_fin
      where c.user_id = auth.uid()
      group by c.id, c.nom, c.modele, c.user_id) x),
    'jours', (select coalesce(json_agg(json_build_object('caisse', c.nom, 'jour', r.jour, 'nb_operations', r.nb_operations,
        'a_classer', r.a_classer, 'ventes', r.ventes, 'achats', r.achats, 'charges', r.charges, 'frais', r.frais,
        'resultat', r.resultat, 'apports', r.apports, 'retraits', r.retraits, 'solde_om', r.solde_om, 'solde_momo', r.solde_momo,
        'especes_compte', r.especes_compte, 'tresorerie', r.tresorerie) order by r.jour, c.nom), '[]'::json)
      from public.resume r join public.caisse c on c.id::text = r.caisse and c.user_id = r.user_id
      where r.user_id = auth.uid() and r.jour between p_debut and p_fin));
end $$;
revoke all on function public.vue_caisses(text, text, text) from public, anon;
grant execute on function public.vue_caisses(text, text, text) to authenticated;
