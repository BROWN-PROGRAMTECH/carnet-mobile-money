-- Carnet Mobile Money · version 0.14 : forfaits modulables par le superadmin et promotions.
-- À exécuter UNE fois dans Supabase → SQL Editor, après ia.sql. Peut être relancé sans danger.
--
-- Forfaits : le superadmin crée, renomme, recompose et retire des packs ; règle le prix mensuel et le prix annuel
-- (formule annuelle) de chaque application ou pack ; règle les limites (jours d'essai, questions IA, SMS lus par l'IA,
-- messages WhatsApp par jour, téléphones d'un pack).
-- Promotions : code promo (le client le saisit), promo ciblée (comptes choisis ou groupe), promo générale (tous),
-- entre deux dates. Réduction : pourcentage, montant fixe, prix spécial, jours offerts ; ou bonus de recharge
-- (+X % crédité sur le porte-monnaie pour une recharge d'au moins N FCFA).
-- Durée : chaque promotion dure un nombre de mois choisi pour chaque client (nombre de recharges pour un bonus).
-- La meilleure promotion applicable est retenue (jamais deux à la fois sur le même paiement).

-- ---------- Forfaits ----------
alter table public.module add column if not exists prix_annuel integer;
alter table public.abonnement add column if not exists periode text not null default 'mois';
do $$ begin
  alter table public.abonnement add constraint abonnement_periode check (periode in ('mois', 'an'));
exception when duplicate_object then null; end $$;
insert into public.parametre (cle, valeur) values ('whatsapp_jour', '30') on conflict (cle) do nothing;

-- ---------- Promotions ----------
create table if not exists public.promotion (
  id uuid primary key default gen_random_uuid(),
  nom text not null,
  description text not null default '',
  cible text not null check (cible in ('code', 'comptes', 'groupe', 'tous')),
  code text unique,
  groupe text check (groupe in ('nouveaux', 'sans_abonnement', 'anciens_abonnes', 'inactifs')),
  comptes uuid[] not null default '{}',
  -- Applications ou packs concernés (vide = tous) ; pour des jours offerts : une seule application ou un seul pack.
  modules text[] not null default '{}',
  type text not null check (type in ('pourcent', 'montant', 'prix', 'jours', 'bonus_recharge')),
  valeur integer not null check (valeur >= 0),
  -- Bonus de recharge : recharge minimum (FCFA).
  seuil integer not null default 0,
  -- Mois de réduction par client (ou nombre de recharges bonifiées pour un bonus).
  duree_mois integer not null default 1 check (duree_mois between 1 and 120),
  debut timestamptz,
  fin timestamptz,
  max_utilisations integer,
  utilisations integer not null default 0,
  actif boolean not null default true,
  cree timestamptz not null default now()
);

create table if not exists public.promo_client (
  user_id uuid not null references auth.users (id) on delete cascade,
  promotion_id uuid not null references public.promotion (id) on delete cascade,
  -- Périodes (ou recharges) encore réduites ; null = pas encore utilisée (code saisi, jamais payé).
  restants integer,
  economie integer not null default 0,
  cree timestamptz not null default now(),
  primary key (user_id, promotion_id)
);

alter table public.recharge add column if not exists bonus integer not null default 0;

alter table public.promotion enable row level security;
alter table public.promo_client enable row level security;
revoke all on public.promotion, public.promo_client from anon, authenticated;

-- Le compte fait-il partie du groupe ?
create or replace function public.dans_groupe(p_user uuid, p_groupe text) returns boolean
language sql stable security definer set search_path = public as $$
  select case p_groupe
    when 'nouveaux' then exists (select 1 from public.profil where user_id = p_user and cree > now() - interval '30 days')
    when 'sans_abonnement' then not exists (select 1 from public.abonnement where user_id = p_user and fin > now())
    when 'anciens_abonnes' then exists (select 1 from public.abonnement where user_id = p_user)
                                and not exists (select 1 from public.abonnement where user_id = p_user and fin > now())
    when 'inactifs' then coalesce((select max(dernier_sync) from public.appareil where user_id = p_user), 0)
                         < (extract(epoch from now() - interval '30 days') * 1000)::bigint
    else false end
$$;

-- Promotion valable maintenant pour ce compte (sans regarder l'application).
create or replace function public.promo_ouverte(p_user uuid, p public.promotion) returns boolean
language plpgsql stable security definer set search_path = public as $$
declare pc public.promo_client;
begin
  if not p.actif or (p.debut is not null and p.debut > now()) or (p.fin is not null and p.fin < now()) then return false; end if;
  select * into pc from public.promo_client where user_id = p_user and promotion_id = p.id;
  if found then return pc.restants is null or pc.restants > 0; end if;
  -- Jamais utilisée par ce compte : il faut être visé et qu'il reste des places.
  if p.max_utilisations is not null and p.utilisations >= p.max_utilisations then return false; end if;
  return case p.cible
    when 'tous' then true
    when 'comptes' then p_user = any (p.comptes)
    when 'groupe' then public.dans_groupe(p_user, p.groupe)
    else false end; -- code : seulement après saisie (ligne promo_client)
end $$;

-- Prix normal d'une période (mois ou année ; Multi-téléphones : par téléphone en plus).
create or replace function public.prix_periode(p_user uuid, p_code text, p_periode text) returns integer
language plpgsql stable security definer set search_path = public as $$
declare m public.module; n integer; v integer;
begin
  select * into m from public.module where code = p_code;
  if not found then return null; end if;
  v := case when p_periode = 'an' then coalesce(m.prix_annuel, m.prix * 12) else m.prix end;
  if m.par_telephone then
    select count(*) into n from public.caisse where user_id = p_user;
    v := v * greatest(1, n - 1);
  end if;
  return v;
end $$;

-- Prix à payer après la meilleure promotion, et la promotion retenue.
create or replace function public.prix_effectif(p_user uuid, p_code text, p_periode text default 'mois')
returns table (prix integer, normal integer, promotion_id uuid, promo_nom text)
language plpgsql stable security definer set search_path = public as $$
declare v_normal integer := public.prix_periode(p_user, p_code, p_periode); p public.promotion; v integer;
        v_best integer; v_id uuid; v_nom text; v_mult integer := case when p_periode = 'an' then 12 else 1 end;
begin
  v_best := v_normal;
  for p in select * from public.promotion where actif and type in ('pourcent', 'montant', 'prix')
             and (cardinality(modules) = 0 or p_code = any (modules)) loop
    if not public.promo_ouverte(p_user, p) then continue; end if;
    v := case p.type
      when 'pourcent' then round(v_normal * (100 - least(100, p.valeur)) / 100.0)::integer
      when 'montant' then greatest(0, v_normal - p.valeur * v_mult)
      when 'prix' then least(v_normal, p.valeur * v_mult) end;
    if v < v_best then v_best := v; v_id := p.id; v_nom := p.nom; end if;
  end loop;
  return query select v_best, v_normal, v_id, v_nom;
end $$;

-- Note l'usage d'une promotion après un paiement réduit.
create or replace function public.consommer_promo(p_user uuid, p_promo uuid, p_economie integer, p_periode text) returns void
language plpgsql security definer set search_path = public as $$
declare v_duree integer; v_conso integer := case when p_periode = 'an' then 12 else 1 end; v_nouveau boolean;
begin
  if p_promo is null then return; end if;
  select duree_mois into v_duree from public.promotion where id = p_promo;
  v_nouveau := not exists (select 1 from public.promo_client where user_id = p_user and promotion_id = p_promo and restants is not null);
  insert into public.promo_client (user_id, promotion_id, restants, economie)
  values (p_user, p_promo, greatest(0, v_duree - v_conso), p_economie)
  on conflict (user_id, promotion_id) do update
    set restants = greatest(0, coalesce(public.promo_client.restants, v_duree) - v_conso),
        economie = public.promo_client.economie + p_economie;
  -- Un code promo est compté à sa saisie ; les autres promotions à leur premier usage.
  if v_nouveau and (select cible from public.promotion where id = p_promo) <> 'code' then
    update public.promotion set utilisations = utilisations + 1 where id = p_promo;
  end if;
end $$;

-- Jours offerts d'une application ou d'un pack (geste commercial, code promo).
create or replace function public.offrir_jours(p_user uuid, p_code text, p_jours integer, p_motif text) returns timestamptz
language plpgsql security definer set search_path = public as $$
declare v_debut timestamptz; v_fin timestamptz;
begin
  if not exists (select 1 from public.module where code = p_code) then raise exception 'Application inconnue.'; end if;
  select greatest(fin, now()) into v_debut from public.abonnement where user_id = p_user and code = p_code;
  v_debut := coalesce(v_debut, now());
  v_fin := v_debut + make_interval(days => p_jours);
  insert into public.abonnement (user_id, code, fin, renouveler, essai_utilise, offert)
  values (p_user, p_code, v_fin, false, true, true)
  on conflict (user_id, code) do update set fin = excluded.fin, maj = now(),
    offert = case when public.abonnement.renouveler then false else true end;
  insert into public.paiement_module (user_id, code, montant, nature, debut, fin, motif)
  values (p_user, p_code, 0, 'offert', v_debut, v_fin, left(coalesce(p_motif, ''), 200));
  return v_fin;
end $$;

create or replace function public.admin_offrir_module(p_user uuid, p_code text, p_jours integer, p_motif text) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  if p_jours is null or p_jours < 1 or p_jours > 366 then raise exception 'Nombre de jours invalide.'; end if;
  perform public.offrir_jours(p_user, p_code, p_jours, p_motif);
end $$;

-- ---------- Abonnement : prix effectif, formule annuelle ----------

create or replace function public.prix_module(p_user uuid, p_code text) returns integer
language sql stable security definer set search_path = public as $$
  select prix from public.prix_effectif(p_user, p_code, 'mois')
$$;

create or replace function public.renouveler_modules(p_user uuid) returns integer
language plpgsql security definer set search_path = public as $$
declare a record; e record; v_debut timestamptz; v_fin timestamptz; v_n integer := 0; v_solde integer;
begin
  for a in select x.*, m.est_pack from public.abonnement x join public.module m on m.code = x.code
            where x.user_id = p_user and x.renouveler and not x.offert and x.fin <= now() and m.actif
            order by m.est_pack desc, m.ordre for update of x loop
    if not a.est_pack and public.pack_actif(p_user, a.code) is not null then continue; end if;
    select * into e from public.prix_effectif(p_user, a.code, a.periode);
    select solde into v_solde from public.profil where user_id = p_user for update;
    if coalesce(v_solde, 0) < e.prix then continue; end if;
    v_debut := case when a.fin < now() - interval '3 days' then now() else a.fin end;
    v_fin := v_debut + case when a.periode = 'an' then interval '1 year' else interval '1 month' end;
    update public.profil set solde = solde - e.prix, maj = now() where user_id = p_user;
    update public.abonnement set fin = v_fin, maj = now() where user_id = p_user and code = a.code;
    insert into public.paiement_module (user_id, code, montant, nature, debut, fin, motif)
    values (p_user, a.code, e.prix, 'renouvellement', v_debut, v_fin, coalesce('Promotion : ' || e.promo_nom, ''));
    perform public.consommer_promo(p_user, e.promotion_id, e.normal - e.prix, a.periode);
    v_n := v_n + 1;
  end loop;
  return v_n;
end $$;

create or replace function public.activer_module(p_code text, p_periode text) returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); m public.module; a public.abonnement; e record; v_pack text; v_solde integer;
        v_essai integer := public.param_int('essai_jours', 14); v_fin timestamptz; v_prix integer;
        v_periode text := case when p_periode = 'an' then 'an' else 'mois' end;
begin
  if v_uid is null then raise exception 'Non connecté.'; end if;
  select * into m from public.module where code = p_code and actif;
  if not found then raise exception 'Application inconnue.'; end if;
  if v_periode = 'an' and m.prix_annuel is null then raise exception 'Pas de formule annuelle pour %.', m.nom; end if;
  insert into public.profil (user_id) values (v_uid) on conflict (user_id) do nothing;
  if (select suspendu from public.profil where user_id = v_uid) then raise exception 'Compte suspendu : contactez le service client.'; end if;
  if not m.est_pack then
    v_pack := public.pack_actif(v_uid, p_code);
    if v_pack is not null then raise exception 'Déjà incluse dans votre %.', (select nom from public.module where code = v_pack); end if;
  end if;
  select * into a from public.abonnement where user_id = v_uid and code = p_code for update;
  if found and a.fin > now() and not (v_periode = 'an' and a.periode = 'mois') then
    update public.abonnement set renouveler = true, offert = false, maj = now() where user_id = v_uid and code = p_code;
    return json_build_object('ok', true, 'nature', 'deja', 'fin', (extract(epoch from a.fin) * 1000)::bigint);
  end if;
  if (not found or not a.essai_utilise) and v_periode = 'mois' then
    v_fin := now() + make_interval(days => v_essai);
    insert into public.abonnement (user_id, code, fin, renouveler, essai_utilise, offert, periode)
    values (v_uid, p_code, v_fin, true, true, false, 'mois')
    on conflict (user_id, code) do update set fin = excluded.fin, renouveler = true, essai_utilise = true, offert = false, periode = 'mois', maj = now();
    insert into public.paiement_module (user_id, code, montant, nature, debut, fin) values (v_uid, p_code, 0, 'essai', now(), v_fin);
  else
    select * into e from public.prix_effectif(v_uid, p_code, v_periode);
    v_prix := e.prix;
    select solde into v_solde from public.profil where user_id = v_uid for update;
    if v_solde < v_prix then
      raise exception 'Solde insuffisant : % FCFA disponibles, % coûte % FCFA. Rechargez votre porte-monnaie.', v_solde, m.nom, v_prix;
    end if;
    -- Passage du mois à l'année pendant un mois en cours : l'année démarre à la fin du mois déjà payé.
    v_fin := greatest(coalesce(a.fin, now()), now()) + case when v_periode = 'an' then interval '1 year' else interval '1 month' end;
    update public.profil set solde = solde - v_prix, maj = now() where user_id = v_uid;
    insert into public.abonnement (user_id, code, fin, renouveler, essai_utilise, offert, periode)
    values (v_uid, p_code, v_fin, true, true, false, v_periode)
    on conflict (user_id, code) do update set fin = excluded.fin, renouveler = true, essai_utilise = true, offert = false, periode = v_periode, maj = now();
    insert into public.paiement_module (user_id, code, montant, nature, debut, fin, motif)
    values (v_uid, p_code, v_prix, 'achat', now(), v_fin, coalesce('Promotion : ' || e.promo_nom, ''));
    perform public.consommer_promo(v_uid, e.promotion_id, e.normal - e.prix, v_periode);
  end if;
  if m.est_pack then
    update public.abonnement set renouveler = false, maj = now() where user_id = v_uid and code = any (m.contenu);
  end if;
  return json_build_object('ok', true, 'nature', case when v_prix is null then 'essai' else 'achat' end,
    'prix', coalesce(v_prix, 0), 'fin', (extract(epoch from v_fin) * 1000)::bigint, 'periode', v_periode);
end $$;

-- Ancienne signature (applications 0.11 à 0.13) : au mois.
create or replace function public.activer_module(p_code text) returns json
language sql security definer set search_path = public as $$ select public.activer_module(p_code, 'mois') $$;

-- Mes applications : prix après promotion, prix annuel, promotions en cours.
create or replace function public.mes_modules() returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'Non connecté.'; end if;
  insert into public.profil (user_id) values (v_uid) on conflict (user_id) do nothing;
  perform public.renouveler_modules(v_uid);
  return json_build_object(
    'catalogue', (select coalesce(json_agg(json_build_object('code', m.code, 'nom', m.nom, 'description', m.description,
        'prix', m.prix, 'prix_compte', e.prix, 'prix_normal', e.normal, 'promo', e.promo_nom,
        'prix_annuel', m.prix_annuel, 'prix_annuel_compte', case when m.prix_annuel is null then null else ea.prix end,
        'par_telephone', m.par_telephone, 'est_pack', m.est_pack, 'contenu', m.contenu, 'telephones', m.telephones)
        order by m.est_pack desc, m.ordre), '[]'::json)
      from public.module m
      cross join lateral public.prix_effectif(v_uid, m.code, 'mois') e
      cross join lateral public.prix_effectif(v_uid, m.code, 'an') ea
      where m.actif),
    'abonnements', (select coalesce(json_agg(json_build_object('code', a.code, 'fin', (extract(epoch from a.fin) * 1000)::bigint,
        'renouveler', a.renouveler, 'essai_utilise', a.essai_utilise, 'offert', a.offert, 'periode', a.periode,
        'essai', exists (select 1 from public.paiement_module p where p.user_id = v_uid and p.code = a.code and p.nature = 'essai' and p.fin = a.fin))), '[]'::json)
      from public.abonnement a where a.user_id = v_uid),
    'promos', (select coalesce(json_agg(json_build_object('nom', p.nom, 'description', p.description, 'type', p.type, 'valeur', p.valeur,
        'seuil', p.seuil, 'modules', p.modules, 'fin', case when p.fin is null then null else (extract(epoch from p.fin) * 1000)::bigint end,
        'restants', (select restants from public.promo_client c where c.user_id = v_uid and c.promotion_id = p.id), 'duree', p.duree_mois)
        order by p.cree desc), '[]'::json)
      from public.promotion p where public.promo_ouverte(v_uid, p)),
    'actifs', to_json(public.modules_actifs(v_uid)),
    'solde', (select solde from public.profil where user_id = v_uid),
    'essai_jours', public.param_int('essai_jours', 14),
    'caisses', (select count(*) from public.caisse where user_id = v_uid),
    'serveur', (extract(epoch from now()) * 1000)::bigint);
end $$;

-- Saisie d'un code promo par le client.
create or replace function public.utiliser_code(p_code text) returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); p public.promotion; v_fin timestamptz; v_cible text;
begin
  if v_uid is null then raise exception 'Non connecté.'; end if;
  select * into p from public.promotion where code = upper(btrim(coalesce(p_code, ''))) and cible = 'code';
  if not found or not p.actif then raise exception 'Code promo inconnu.'; end if;
  if p.debut is not null and p.debut > now() then raise exception 'Ce code n''est pas encore valable.'; end if;
  if p.fin is not null and p.fin < now() then raise exception 'Ce code a expiré.'; end if;
  if exists (select 1 from public.promo_client where user_id = v_uid and promotion_id = p.id) then raise exception 'Code déjà utilisé sur ce compte.'; end if;
  if p.max_utilisations is not null and p.utilisations >= p.max_utilisations then raise exception 'Ce code a déjà été utilisé le nombre de fois prévu.'; end if;
  if p.type = 'jours' then
    v_cible := p.modules[1];
    if v_cible is null then raise exception 'Code mal configuré : application manquante.'; end if;
    v_fin := public.offrir_jours(v_uid, v_cible, p.valeur, 'Code promo ' || p.code);
    insert into public.promo_client (user_id, promotion_id, restants) values (v_uid, p.id, 0);
    update public.promotion set utilisations = utilisations + 1 where id = p.id;
    return json_build_object('ok', true, 'message', p.valeur || ' jours offerts : ' || (select nom from public.module where code = v_cible)
      || ' jusqu''au ' || to_char(v_fin, 'DD/MM/YYYY') || '.');
  end if;
  insert into public.promo_client (user_id, promotion_id, restants) values (v_uid, p.id, null);
  update public.promotion set utilisations = utilisations + 1 where id = p.id;
  return json_build_object('ok', true, 'message', 'Code « ' || p.code || ' » enregistré : ' || p.nom
    || case when p.type = 'bonus_recharge' then '. Il s''applique à vos prochaines recharges.' else '. La réduction s''applique à votre prochain paiement.' end);
end $$;

-- ---------- Bonus de recharge ----------

-- Crédite une recharge validée (montant + meilleur bonus applicable). Renvoie le bonus.
create or replace function public.crediter_recharge(p_id bigint) returns integer
language plpgsql security definer set search_path = public as $$
declare r public.recharge; p public.promotion; v_bonus integer := 0; v_best uuid; v integer; v_nouveau boolean;
begin
  select * into r from public.recharge where id = p_id;
  if r.operateur in ('OM', 'MOMO') and r.montant > 0 then
    for p in select * from public.promotion where type = 'bonus_recharge' and actif and seuil <= r.montant loop
      if not public.promo_ouverte(r.user_id, p) then continue; end if;
      v := floor(r.montant * least(100, p.valeur) / 100.0)::integer;
      if v > v_bonus then v_bonus := v; v_best := p.id; end if;
    end loop;
  end if;
  update public.profil set solde = solde + r.montant + v_bonus, maj = now() where user_id = r.user_id;
  if v_bonus > 0 then
    update public.recharge set bonus = v_bonus, motif = trim(both ' ' from motif || ' Bonus +' || v_bonus || ' FCFA') where id = p_id;
    v_nouveau := not exists (select 1 from public.promo_client where user_id = r.user_id and promotion_id = v_best and restants is not null);
    insert into public.promo_client (user_id, promotion_id, restants, economie)
    values (r.user_id, v_best, greatest(0, (select duree_mois from public.promotion where id = v_best) - 1), v_bonus)
    on conflict (user_id, promotion_id) do update
      set restants = greatest(0, coalesce(public.promo_client.restants, (select duree_mois from public.promotion where id = v_best)) - 1),
          economie = public.promo_client.economie + v_bonus;
    if v_nouveau and (select cible from public.promotion where id = v_best) <> 'code' then
      update public.promotion set utilisations = utilisations + 1 where id = v_best;
    end if;
  end if;
  return v_bonus;
end $$;

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
      perform public.crediter_recharge(r.id);
      v_n := v_n + 1;
    end if;
  end loop;
  return v_n;
end $$;

create or replace function public.admin_valider_recharge(p_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare r public.recharge;
begin
  perform public.verifier_admin();
  select * into r from public.recharge where id = p_id for update;
  if not found then raise exception 'Recharge introuvable.'; end if;
  if r.statut <> 'en_attente' then raise exception 'Recharge déjà traitée.'; end if;
  update public.recharge set statut = 'valide', traite = now() where id = p_id;
  perform public.crediter_recharge(p_id);
  update public.recu set recharge_id = p_id
   where recharge_id is null and operateur = r.operateur and reference = public.ref_norm(r.reference);
end $$;

-- ---------- Limites réglables ----------

create or replace function public.admin_parametre(p_cle text, p_valeur text) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  if p_cle not in ('prix_export_petit', 'prix_export_grand', 'seuil_export', 'exports_offerts', 'recharge_min',
                   'numero_om', 'numero_momo', 'nom_marchand', 'ussd_om', 'ussd_momo',
                   'essai_jours', 'ia_quota', 'ia_sms_mois', 'whatsapp_jour') then
    raise exception 'Paramètre inconnu.';
  end if;
  if p_cle in ('ussd_om', 'ussd_momo') and coalesce(p_valeur, '') !~ '^[*#][0-9*#{}a-z]*#$' then
    raise exception 'Code USSD invalide : il commence par * ou # et finit par #.';
  end if;
  if p_cle in ('essai_jours', 'ia_quota', 'ia_sms_mois', 'whatsapp_jour') and coalesce(p_valeur, '') !~ '^[0-9]{1,5}$' then
    raise exception 'Nombre entier attendu.';
  end if;
  insert into public.parametre (cle, valeur) values (p_cle, coalesce(p_valeur, ''))
  on conflict (cle) do update set valeur = excluded.valeur;
end $$;

create or replace function public.signaler_alerte(p_type text, p_cle text, p_params json) returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); pr public.alerte_pref; v_modele text; v_params jsonb := '[]'; e json; v_txt text;
        v_n integer := 0; v_c integer; v_jour integer; d record; v_max integer := public.param_int('whatsapp_jour', 30);
begin
  if v_uid is null then raise exception 'Non connecté.'; end if;
  if not ('ALERTES' = any (public.modules_actifs(v_uid))) then
    return json_build_object('ok', false, 'raison', 'Application Alertes WhatsApp non active.');
  end if;
  insert into public.alerte_pref (user_id) values (v_uid) on conflict (user_id) do nothing;
  select * into pr from public.alerte_pref where user_id = v_uid;
  v_modele := case p_type when 'bilan' then 'bilan_du_soir' when 'ecart' then 'ecart_caisse'
                          when 'chaine' then 'alerte_compte' when 'solde' then 'alerte_compte'
                          when 'dettes' then 'echeances_dettes' end;
  if v_modele is null then raise exception 'Type d''alerte inconnu.'; end if;
  if (p_type = 'bilan' and not pr.bilan) or (p_type = 'ecart' and not pr.ecart) or (p_type = 'chaine' and not pr.chaine)
     or (p_type = 'solde' and not pr.solde) or (p_type = 'dettes' and not pr.dettes) then
    return json_build_object('ok', false, 'raison', 'Alerte désactivée dans les réglages.');
  end if;
  if coalesce(p_cle, '') = '' or length(p_cle) > 200 then raise exception 'Clé d''alerte invalide.'; end if;
  if json_typeof(p_params) <> 'array' or json_array_length(p_params) > 8 then raise exception 'Paramètres invalides.'; end if;
  for e in select * from json_array_elements(p_params) loop
    v_txt := left(regexp_replace(regexp_replace(coalesce(e #>> '{}', ''), '[\r\n\t]+', ' ', 'g'), ' {4,}', '   ', 'g'), 400);
    if btrim(v_txt) = '' then v_txt := '-'; end if;
    v_params := v_params || to_jsonb(v_txt);
  end loop;
  select count(*) into v_jour from public.whatsapp_envoi where user_id = v_uid and cree > now() - interval '24 hours';
  for d in select numero from public.destinataire where user_id = v_uid and confirme order by cree loop
    if v_jour + v_n >= v_max then exit; end if;
    insert into public.whatsapp_envoi (user_id, numero, type, modele, params, cle)
    values (v_uid, d.numero, p_type, v_modele, v_params, p_cle)
    on conflict (user_id, numero, cle) do nothing;
    get diagnostics v_c = row_count;
    v_n := v_n + v_c;
  end loop;
  return json_build_object('ok', true, 'deposees', v_n);
end $$;

-- ---------- Superadmin : forfaits ----------

-- Crée ou modifie une application ou un pack. Seuls les packs peuvent être créés (une application demande du code).
create or replace function public.admin_enregistrer_module(p json) returns void
language plpgsql security definer set search_path = public as $$
declare v_code text := upper(btrim(coalesce(p->>'code', ''))); m public.module; v_contenu text[]; x text;
begin
  perform public.verifier_admin();
  select * into m from public.module where code = v_code;
  if not found then
    if v_code !~ '^PACK_[A-Z0-9_]{2,30}$' then raise exception 'Code de pack invalide : PACK_ suivi de lettres (ex. PACK_AGENT).'; end if;
    insert into public.module (code, nom, est_pack, ordre) values (v_code, coalesce(nullif(btrim(p->>'nom'), ''), v_code), true, 9) returning * into m;
  end if;
  if (p->>'prix') is not null and ((p->>'prix')::integer < 0 or (p->>'prix')::integer > 1000000) then raise exception 'Prix invalide.'; end if;
  if m.est_pack and p->'contenu' is not null then
    select coalesce(array_agg(value), '{}') into v_contenu from json_array_elements_text(p->'contenu');
    foreach x in array v_contenu loop
      if not exists (select 1 from public.module where code = x and not est_pack) then raise exception 'Application inconnue dans le pack : %.', x; end if;
    end loop;
    if cardinality(v_contenu) < 2 then raise exception 'Un pack contient au moins deux applications.'; end if;
  end if;
  update public.module set
    nom = coalesce(nullif(btrim(p->>'nom'), ''), nom),
    description = coalesce(p->>'description', description),
    prix = coalesce((p->>'prix')::integer, prix),
    prix_annuel = case when (p::jsonb) ? 'prix_annuel' then nullif(p->>'prix_annuel', '')::integer else prix_annuel end,
    contenu = case when est_pack and v_contenu is not null then v_contenu else contenu end,
    telephones = greatest(1, least(50, coalesce((p->>'telephones')::integer, telephones))),
    ordre = coalesce((p->>'ordre')::integer, ordre),
    actif = coalesce((p->>'actif')::boolean, actif)
  where code = v_code;
end $$;

-- Retire un pack : supprimé s'il n'a jamais servi, sinon retiré du catalogue (les abonnés vont au bout de leur période).
create or replace function public.admin_supprimer_module(p_code text) returns text
language plpgsql security definer set search_path = public as $$
declare m public.module;
begin
  perform public.verifier_admin();
  select * into m from public.module where code = p_code;
  if not found then raise exception 'Pack inconnu.'; end if;
  if not m.est_pack then raise exception 'Seuls les packs se suppriment ; une application se retire du catalogue.'; end if;
  if exists (select 1 from public.abonnement where code = p_code) then
    update public.module set actif = false where code = p_code;
    return 'Pack retiré du catalogue : les abonnés le gardent jusqu''à la fin de leur période, sans renouvellement.';
  end if;
  delete from public.module where code = p_code;
  return 'Pack supprimé.';
end $$;

create or replace function public.admin_modules() returns json
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return json_build_object(
    'catalogue', (select coalesce(json_agg(json_build_object('code', m.code, 'nom', m.nom, 'description', m.description, 'prix', m.prix,
        'prix_annuel', m.prix_annuel, 'par_telephone', m.par_telephone, 'est_pack', m.est_pack, 'contenu', m.contenu, 'telephones', m.telephones,
        'ordre', m.ordre, 'actif', m.actif,
        'abonnes', (select count(*) from public.abonnement a where a.code = m.code and a.fin > now()),
        'annuels', (select count(*) from public.abonnement a where a.code = m.code and a.fin > now() and a.periode = 'an'),
        'essais', (select count(*) from public.abonnement a where a.code = m.code and a.fin > now()
                     and exists (select 1 from public.paiement_module p where p.user_id = a.user_id and p.code = a.code and p.nature = 'essai' and p.fin = a.fin)),
        'revenu_mois', (select coalesce(sum(p.montant), 0) from public.paiement_module p where p.code = m.code and p.cree >= date_trunc('month', now())))
        order by m.est_pack desc, m.ordre), '[]'::json) from public.module m),
    'revenu_mois', (select coalesce(sum(montant), 0) from public.paiement_module where cree >= date_trunc('month', now())),
    'limites', (select json_object_agg(cle, valeur) from public.parametre where cle in ('essai_jours', 'ia_quota', 'ia_sms_mois', 'whatsapp_jour')),
    'comptes', (select coalesce(json_agg(json_build_object('user_id', u.user_id, 'actifs', public.modules_actifs(u.user_id),
        'fin', (select min(fin) from public.abonnement a where a.user_id = u.user_id and a.fin > now()))), '[]'::json)
      from public.profil u));
end $$;

-- ---------- Superadmin : promotions ----------

create or replace function public.admin_promotions() returns json
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return (select coalesce(json_agg(json_build_object('id', p.id, 'nom', p.nom, 'description', p.description, 'cible', p.cible, 'code', p.code,
      'groupe', p.groupe, 'comptes', p.comptes, 'modules', p.modules, 'type', p.type, 'valeur', p.valeur, 'seuil', p.seuil,
      'duree_mois', p.duree_mois, 'debut', p.debut, 'fin', p.fin, 'max_utilisations', p.max_utilisations, 'utilisations', p.utilisations,
      'actif', p.actif, 'cree', p.cree,
      'clients', (select count(*) from public.promo_client c where c.promotion_id = p.id),
      'economie', (select coalesce(sum(economie), 0) from public.promo_client c where c.promotion_id = p.id))
      order by p.actif desc, p.cree desc), '[]'::json) from public.promotion p);
end $$;

create or replace function public.admin_enregistrer_promotion(p json) returns uuid
language plpgsql security definer set search_path = public as $$
declare v_id uuid := nullif(p->>'id', '')::uuid; v_cible text := p->>'cible'; v_type text := p->>'type';
        v_code text := nullif(upper(btrim(coalesce(p->>'code', ''))), ''); v_modules text[]; v_comptes uuid[]; x text;
begin
  perform public.verifier_admin();
  if coalesce(btrim(p->>'nom'), '') = '' then raise exception 'Donnez un nom à la promotion.'; end if;
  if v_cible not in ('code', 'comptes', 'groupe', 'tous') then raise exception 'Cible inconnue.'; end if;
  if v_type not in ('pourcent', 'montant', 'prix', 'jours', 'bonus_recharge') then raise exception 'Type de réduction inconnu.'; end if;
  if v_cible = 'code' and (v_code is null or v_code !~ '^[A-Z0-9_-]{3,20}$') then raise exception 'Code promo : 3 à 20 lettres ou chiffres (ex. RENTREE25).'; end if;
  if v_cible <> 'code' then v_code := null; end if;
  if v_type = 'jours' and v_cible <> 'code' then raise exception 'Des jours offerts à des comptes choisis se donnent avec « Offrir » (onglet Comptes) ; ici, seulement par code promo.'; end if;
  if v_type = 'pourcent' and (p->>'valeur')::integer > 100 then raise exception 'Pourcentage entre 1 et 100.'; end if;
  select coalesce(array_agg(value), '{}') into v_modules from json_array_elements_text(coalesce(p->'modules', '[]'::json));
  foreach x in array v_modules loop
    if not exists (select 1 from public.module where code = x) then raise exception 'Application inconnue : %.', x; end if;
  end loop;
  if v_type = 'jours' and cardinality(v_modules) <> 1 then raise exception 'Jours offerts : choisissez une seule application ou un seul pack.'; end if;
  select coalesce(array_agg(value::uuid), '{}') into v_comptes from json_array_elements_text(coalesce(p->'comptes', '[]'::json));
  if v_cible = 'comptes' and cardinality(v_comptes) = 0 then raise exception 'Choisissez au moins un compte.'; end if;
  if v_cible = 'groupe' and coalesce(p->>'groupe', '') not in ('nouveaux', 'sans_abonnement', 'anciens_abonnes', 'inactifs') then raise exception 'Groupe inconnu.'; end if;
  if v_code is not null and exists (select 1 from public.promotion where code = v_code and id is distinct from v_id) then raise exception 'Ce code existe déjà.'; end if;
  if v_id is null then
    insert into public.promotion (nom, description, cible, code, groupe, comptes, modules, type, valeur, seuil, duree_mois, debut, fin, max_utilisations, actif)
    values (btrim(p->>'nom'), coalesce(p->>'description', ''), v_cible, v_code, case when v_cible = 'groupe' then p->>'groupe' end, v_comptes, v_modules,
            v_type, coalesce((p->>'valeur')::integer, 0), coalesce((p->>'seuil')::integer, 0), greatest(1, coalesce((p->>'duree_mois')::integer, 1)),
            nullif(p->>'debut', '')::timestamptz, nullif(p->>'fin', '')::timestamptz, nullif(p->>'max_utilisations', '')::integer,
            coalesce((p->>'actif')::boolean, true))
    returning id into v_id;
  else
    update public.promotion set nom = btrim(p->>'nom'), description = coalesce(p->>'description', ''), cible = v_cible, code = v_code,
      groupe = case when v_cible = 'groupe' then p->>'groupe' end, comptes = v_comptes, modules = v_modules, type = v_type,
      valeur = coalesce((p->>'valeur')::integer, 0), seuil = coalesce((p->>'seuil')::integer, 0),
      duree_mois = greatest(1, coalesce((p->>'duree_mois')::integer, 1)), debut = nullif(p->>'debut', '')::timestamptz,
      fin = nullif(p->>'fin', '')::timestamptz, max_utilisations = nullif(p->>'max_utilisations', '')::integer,
      actif = coalesce((p->>'actif')::boolean, true)
    where id = v_id;
    if not found then raise exception 'Promotion introuvable.'; end if;
  end if;
  return v_id;
end $$;

create or replace function public.admin_arreter_promotion(p_id uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  update public.promotion set actif = false where id = p_id;
end $$;

-- ---------- Droits ----------
do $$
declare f text;
begin
  foreach f in array array['activer_module(text, text)', 'activer_module(text)', 'mes_modules()', 'utiliser_code(text)',
                           'admin_valider_recharge(bigint)', 'admin_parametre(text, text)', 'signaler_alerte(text, text, json)',
                           'admin_enregistrer_module(json)', 'admin_supprimer_module(text)', 'admin_modules()', 'admin_promotions()',
                           'admin_enregistrer_promotion(json)', 'admin_arreter_promotion(uuid)', 'admin_offrir_module(uuid, text, integer, text)'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to authenticated', f);
  end loop;
  foreach f in array array['dans_groupe(uuid, text)', 'promo_ouverte(uuid, public.promotion)', 'prix_periode(uuid, text, text)',
                           'prix_effectif(uuid, text, text)', 'consommer_promo(uuid, uuid, integer, text)', 'offrir_jours(uuid, text, integer, text)',
                           'prix_module(uuid, text)', 'renouveler_modules(uuid)', 'crediter_recharge(bigint)', 'rapprocher_recharges()'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
  end loop;
end $$;
