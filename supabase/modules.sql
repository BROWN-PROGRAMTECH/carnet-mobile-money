-- Carnet Mobile Money · version 0.11 : applications (modules) à la carte, abonnement mensuel.
-- À exécuter UNE fois dans Supabase → SQL Editor, après suivi.sql. Peut être relancé sans danger.
--
-- Base gratuite pour tous : lecture des SMS (OM, MoMo, banques), journal, classement, entrées et dépenses par
-- catégorie, soldes, sauvegarde. Chaque application en plus se paie au mois depuis le porte-monnaie prépayé :
--  - 14 jours d'essai gratuit à la première activation de chaque application ou pack ;
--  - renouvellement automatique si le solde suffit (vérifié à chaque ouverture de l'application) ;
--  - non renouvelée : l'application passe en lecture seule dans le téléphone, aucune donnée n'est perdue ;
--  - un pack regroupe plusieurs applications à prix réduit ;
--  - l'export Excel est inclus avec « Finances & budgets » ; sans elle, il reste payé au fichier.
-- Prix et contenu modifiables dans le tableau de bord (onglet Applications), sans nouvelle version de l'application.

-- ---------- Catalogue ----------
create table if not exists public.module (
  code text primary key,
  nom text not null,
  description text not null default '',
  prix integer not null default 0,
  -- Multi-téléphones : prix × téléphones en plus du premier.
  par_telephone boolean not null default false,
  est_pack boolean not null default false,
  contenu text[] not null default '{}',
  -- Pack : nombre de téléphones couverts (Multi-téléphones inclus jusqu'à ce nombre).
  telephones integer not null default 1,
  ordre integer not null default 100,
  actif boolean not null default true
);

insert into public.module (code, nom, description, prix, par_telephone, est_pack, contenu, telephones, ordre) values
  ('CAISSE', 'Caisse commerçant', 'Espèces, fond de caisse, écart réel / théorique, journée de caisse, mode agent OM et MoMo.', 1000, false, false, '{}', 1, 10),
  ('FINANCES', 'Finances & budgets', 'Trésorerie, résultat du mois, budgets par poste, comparaison avec le mois précédent, meilleurs clients. Export Excel inclus.', 1000, false, false, '{}', 1, 20),
  ('SUIVI', 'Suivi de l''argent', 'Salaire ou argent reçu suivi jusqu''à la dernière dépense, retraits répartis, reste à justifier.', 500, false, false, '{}', 1, 30),
  ('DETTES', 'Dettes & créances', 'Qui me doit, à qui je dois, échéances et retards, par personne.', 500, false, false, '{}', 1, 40),
  ('MULTI', 'Multi-téléphones', 'Une caisse par téléphone, code patron, vue « Toutes mes caisses ». Prix par téléphone en plus du premier.', 1000, true, false, '{}', 1, 50),
  ('PACK_PARTICULIER', 'Pack Particulier', 'Suivi de l''argent + Dettes & créances.', 800, false, true, '{SUIVI,DETTES}', 1, 1),
  ('PACK_COMMERCANT', 'Pack Commerçant', 'Caisse commerçant + Finances & budgets + Dettes & créances.', 2000, false, true, '{CAISSE,FINANCES,DETTES}', 1, 2),
  ('PACK_ENTREPRISE', 'Pack Entreprise', 'Toutes les applications, jusqu''à 3 téléphones.', 4000, false, true, '{CAISSE,FINANCES,SUIVI,DETTES,MULTI}', 3, 3)
on conflict (code) do nothing;

insert into public.parametre (cle, valeur) values ('essai_jours', '14') on conflict (cle) do nothing;

-- ---------- Abonnements ----------
create table if not exists public.abonnement (
  user_id uuid not null references auth.users (id) on delete cascade,
  code text not null references public.module (code),
  fin timestamptz not null,
  renouveler boolean not null default true,
  essai_utilise boolean not null default false,
  -- Période en cours offerte (mois de lancement, geste commercial) : pas de renouvellement sans accord.
  offert boolean not null default false,
  maj timestamptz not null default now(),
  primary key (user_id, code)
);
create index if not exists abonnement_code on public.abonnement (code);

create table if not exists public.paiement_module (
  id bigserial primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  code text not null,
  montant integer not null,
  nature text not null check (nature in ('essai', 'achat', 'renouvellement', 'offert')),
  debut timestamptz not null,
  fin timestamptz not null,
  motif text not null default '',
  cree timestamptz not null default now()
);
create index if not exists paiement_module_user on public.paiement_module (user_id, cree);
create index if not exists paiement_module_cree on public.paiement_module (cree);

alter table public.module enable row level security;
alter table public.abonnement enable row level security;
alter table public.paiement_module enable row level security;
-- Catalogue lisible par les comptes connectés ; abonnements et paiements uniquement par les fonctions ci-dessous.
drop policy if exists lecture on public.module;
create policy lecture on public.module for select to authenticated using (true);
revoke all on public.module, public.abonnement, public.paiement_module from anon, authenticated;
grant select on public.module to authenticated;

alter table public.export add column if not exists inclus boolean not null default false;

-- ---------- Calculs ----------

-- Applications actives d'un compte (packs dépliés).
create or replace function public.modules_actifs(p_user uuid) returns text[]
language sql stable security definer set search_path = public as $$
  select coalesce(array_agg(distinct x), '{}') from (
    select unnest(case when m.est_pack then m.contenu else array[m.code] end) as x
      from public.abonnement a join public.module m on m.code = a.code
     where a.user_id = p_user and a.fin > now()) t
$$;

-- Pack actif qui contient une application (null si aucun).
create or replace function public.pack_actif(p_user uuid, p_code text) returns text
language sql stable security definer set search_path = public as $$
  select m.code from public.abonnement a join public.module m on m.code = a.code
   where a.user_id = p_user and a.fin > now() and m.est_pack and p_code = any (m.contenu)
   order by a.fin desc limit 1
$$;

-- Prix mensuel d'une application pour ce compte (Multi-téléphones : par téléphone en plus du premier).
create or replace function public.prix_module(p_user uuid, p_code text) returns integer
language plpgsql stable security definer set search_path = public as $$
declare m public.module; n integer;
begin
  select * into m from public.module where code = p_code;
  if not found then return null; end if;
  if not m.par_telephone then return m.prix; end if;
  select count(*) into n from public.caisse where user_id = p_user;
  return m.prix * greatest(1, n - 1);
end $$;

-- Renouvelle les abonnements arrivés à terme si le solde suffit (appelée à chaque ouverture).
create or replace function public.renouveler_modules(p_user uuid) returns integer
language plpgsql security definer set search_path = public as $$
declare a record; v_prix integer; v_debut timestamptz; v_n integer := 0; v_solde integer;
begin
  for a in select x.*, m.est_pack from public.abonnement x join public.module m on m.code = x.code
            where x.user_id = p_user and x.renouveler and not x.offert and x.fin <= now() and m.actif
            order by m.est_pack desc, m.ordre for update of x loop
    -- Déjà couverte par un pack actif : on ne fait pas payer deux fois.
    if not a.est_pack and public.pack_actif(p_user, a.code) is not null then continue; end if;
    v_prix := public.prix_module(p_user, a.code);
    select solde into v_solde from public.profil where user_id = p_user for update;
    if coalesce(v_solde, 0) < v_prix then continue; end if;
    -- Reprise après une longue absence : le nouveau mois part d'aujourd'hui.
    v_debut := case when a.fin < now() - interval '3 days' then now() else a.fin end;
    update public.profil set solde = solde - v_prix, maj = now() where user_id = p_user;
    update public.abonnement set fin = v_debut + interval '1 month', maj = now() where user_id = p_user and code = a.code;
    insert into public.paiement_module (user_id, code, montant, nature, debut, fin)
    values (p_user, a.code, v_prix, 'renouvellement', v_debut, v_debut + interval '1 month');
    v_n := v_n + 1;
  end loop;
  return v_n;
end $$;

-- Mes applications : catalogue, abonnements, applications actives, solde. Renouvelle d'abord ce qui doit l'être.
create or replace function public.mes_modules() returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'Non connecté.'; end if;
  insert into public.profil (user_id) values (v_uid) on conflict (user_id) do nothing;
  perform public.renouveler_modules(v_uid);
  return json_build_object(
    'catalogue', (select coalesce(json_agg(json_build_object('code', m.code, 'nom', m.nom, 'description', m.description,
        'prix', m.prix, 'prix_compte', public.prix_module(v_uid, m.code), 'par_telephone', m.par_telephone,
        'est_pack', m.est_pack, 'contenu', m.contenu, 'telephones', m.telephones) order by m.est_pack desc, m.ordre), '[]'::json)
      from public.module m where m.actif),
    'abonnements', (select coalesce(json_agg(json_build_object('code', a.code, 'fin', (extract(epoch from a.fin) * 1000)::bigint,
        'renouveler', a.renouveler, 'essai_utilise', a.essai_utilise, 'offert', a.offert,
        'essai', exists (select 1 from public.paiement_module p where p.user_id = v_uid and p.code = a.code and p.nature = 'essai' and p.fin = a.fin))), '[]'::json)
      from public.abonnement a where a.user_id = v_uid),
    'actifs', to_json(public.modules_actifs(v_uid)),
    'solde', (select solde from public.profil where user_id = v_uid),
    'essai_jours', public.param_int('essai_jours', 14),
    'caisses', (select count(*) from public.caisse where user_id = v_uid),
    'serveur', (extract(epoch from now()) * 1000)::bigint);
end $$;

-- Activer une application ou un pack : essai gratuit la première fois, sinon paiement d'un mois.
-- Déjà active : relance simplement le renouvellement automatique.
create or replace function public.activer_module(p_code text) returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); m public.module; a public.abonnement; v_prix integer; v_pack text; v_solde integer;
        v_essai integer := public.param_int('essai_jours', 14); v_fin timestamptz;
begin
  if v_uid is null then raise exception 'Non connecté.'; end if;
  select * into m from public.module where code = p_code and actif;
  if not found then raise exception 'Application inconnue.'; end if;
  insert into public.profil (user_id) values (v_uid) on conflict (user_id) do nothing;
  if (select suspendu from public.profil where user_id = v_uid) then raise exception 'Compte suspendu : contactez le service client.'; end if;
  if not m.est_pack then
    v_pack := public.pack_actif(v_uid, p_code);
    if v_pack is not null then
      raise exception 'Déjà incluse dans votre %.', (select nom from public.module where code = v_pack);
    end if;
  end if;
  select * into a from public.abonnement where user_id = v_uid and code = p_code for update;
  if found and a.fin > now() then
    update public.abonnement set renouveler = true, offert = false, maj = now() where user_id = v_uid and code = p_code;
    return json_build_object('ok', true, 'nature', 'deja', 'fin', (extract(epoch from a.fin) * 1000)::bigint);
  end if;
  if not found or not a.essai_utilise then
    v_fin := now() + make_interval(days => v_essai);
    insert into public.abonnement (user_id, code, fin, renouveler, essai_utilise, offert)
    values (v_uid, p_code, v_fin, true, true, false)
    on conflict (user_id, code) do update set fin = excluded.fin, renouveler = true, essai_utilise = true, offert = false, maj = now();
    insert into public.paiement_module (user_id, code, montant, nature, debut, fin) values (v_uid, p_code, 0, 'essai', now(), v_fin);
  else
    v_prix := public.prix_module(v_uid, p_code);
    select solde into v_solde from public.profil where user_id = v_uid for update;
    if v_solde < v_prix then
      raise exception 'Solde insuffisant : % FCFA disponibles, % coûte % FCFA par mois. Rechargez votre porte-monnaie.', v_solde, m.nom, v_prix;
    end if;
    v_fin := now() + interval '1 month';
    update public.profil set solde = solde - v_prix, maj = now() where user_id = v_uid;
    update public.abonnement set fin = v_fin, renouveler = true, offert = false, maj = now() where user_id = v_uid and code = p_code;
    insert into public.paiement_module (user_id, code, montant, nature, debut, fin) values (v_uid, p_code, v_prix, 'achat', now(), v_fin);
  end if;
  -- Un pack remplace les applications qu'il contient : elles ne se renouvellent plus à part.
  if m.est_pack then
    update public.abonnement set renouveler = false, maj = now() where user_id = v_uid and code = any (m.contenu);
  end if;
  return json_build_object('ok', true, 'nature', case when v_prix is null then 'essai' else 'achat' end,
    'prix', coalesce(v_prix, 0), 'fin', (extract(epoch from v_fin) * 1000)::bigint);
end $$;

-- Arrêter le renouvellement : l'application reste active jusqu'à la fin de la période payée.
create or replace function public.arreter_module(p_code text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  update public.abonnement set renouveler = false, maj = now() where user_id = auth.uid() and code = p_code;
end $$;

-- ---------- Règles appliquées par le serveur ----------

-- Export Excel : gratuit avec Finances & budgets ; sinon payé au fichier comme avant.
create or replace function public.payer_export(p_id uuid, p_nb integer, p_periode text, p_device text,
                                               p_date bigint default null, p_hors_ligne boolean default false) returns json
language plpgsql security definer set search_path = public as $$
declare p public.profil;
        e public.export;
        v_prix integer;
        v_offert boolean := false;
        v_inclus boolean := false;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  if p_id is null or p_nb is null or p_nb < 0 then raise exception 'Demande d''export invalide.'; end if;
  insert into public.profil (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  select * into p from public.profil where user_id = auth.uid() for update;

  select * into e from public.export where id = p_id;
  if found then
    if e.user_id <> auth.uid() then raise exception 'Identifiant d''export déjà utilisé.'; end if;
    return json_build_object('ok', true, 'deja', true, 'prix', e.prix, 'offert', e.offert, 'inclus', e.inclus, 'solde', p.solde,
      'offerts_restants', greatest(0, public.param_int('exports_offerts', 1) - p.offerts_utilises));
  end if;

  if p.suspendu and not p_hors_ligne then raise exception 'Compte suspendu : contactez le service client.'; end if;

  v_prix := public.prix_export(p_nb);
  if 'FINANCES' = any (public.modules_actifs(auth.uid())) then
    v_inclus := true;
    v_prix := 0;
  elsif v_prix > 0 and p.offerts_utilises < public.param_int('exports_offerts', 1) then
    v_offert := true;
    v_prix := 0;
  end if;
  if v_prix > 0 and p.solde < v_prix and not p_hors_ligne then
    raise exception 'Solde insuffisant : % FCFA disponibles, cet export coûte % FCFA. Rechargez votre porte-monnaie, ou activez Finances & budgets (exports inclus).', p.solde, v_prix;
  end if;

  update public.profil
     set solde = solde - v_prix, offerts_utilises = offerts_utilises + case when v_offert then 1 else 0 end, maj = now()
   where user_id = auth.uid()
   returning * into p;
  insert into public.export (id, user_id, device_id, nb_operations, periode, prix, offert, inclus, hors_ligne, date_export)
  values (p_id, auth.uid(), p_device, p_nb, left(coalesce(p_periode, ''), 120), v_prix, v_offert, v_inclus, p_hors_ligne,
          case when p_date is null then now() else to_timestamp(p_date / 1000.0) end);

  return json_build_object('ok', true, 'deja', false, 'prix', v_prix, 'offert', v_offert, 'inclus', v_inclus, 'solde', p.solde,
    'offerts_restants', greatest(0, public.param_int('exports_offerts', 1) - p.offerts_utilises));
end $$;

-- Nouvelle caisse (deuxième téléphone et plus) : demande Multi-téléphones ; avec un pack, dans la limite du pack.
create or replace function public.creer_caisse(p_nom text, p_device text, p_modele text) returns json
language plpgsql security definer set search_path = public as $$
declare v_nom text := left(btrim(coalesce(p_nom, '')), 40);
        c public.caisse;
        v_n integer;
        v_pack text;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  if v_nom = '' then raise exception 'Donnez un nom à la caisse.'; end if;
  select count(*) into v_n from public.caisse where user_id = auth.uid();
  if v_n >= 20 then raise exception 'Maximum 20 caisses par compte.'; end if;
  if v_n >= 1 then
    if exists (select 1 from public.abonnement where user_id = auth.uid() and code = 'MULTI' and fin > now()) then
      null; -- Multi-téléphones à part : chaque téléphone en plus est compté au renouvellement.
    else
      v_pack := public.pack_actif(auth.uid(), 'MULTI');
      if v_pack is null then
        raise exception 'Un deuxième téléphone demande l''application Multi-téléphones (Compte → Applications). Vous pouvez aussi reprendre la caisse d''un ancien téléphone.';
      end if;
      if v_n >= (select telephones from public.module where code = v_pack) then
        raise exception 'Votre pack couvre % téléphones. Ajoutez l''application Multi-téléphones pour en ajouter.', (select telephones from public.module where code = v_pack);
      end if;
    end if;
  end if;
  if exists (select 1 from public.caisse where user_id = auth.uid() and lower(nom) = lower(v_nom)) then
    raise exception 'Une caisse porte déjà ce nom.';
  end if;
  insert into public.caisse (user_id, nom, device_id, modele) values (auth.uid(), v_nom, p_device, p_modele) returning * into c;
  return json_build_object('id', c.id, 'nom', c.nom);
end $$;

-- Porte-monnaie : l'historique montre aussi les paiements d'applications.
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
    'exports_inclus', 'FINANCES' = any (public.modules_actifs(auth.uid())),
    'en_attente', (select coalesce(sum(montant), 0) from public.recharge where user_id = auth.uid() and statut = 'en_attente'),
    'historique', (select coalesce(json_agg(h order by h.quand desc), '[]'::json) from (
        (select 'recharge' as nature, r.montant, r.statut, r.motif, r.operateur as detail,
                (extract(epoch from r.cree) * 1000)::bigint as quand
           from public.recharge r where r.user_id = auth.uid() order by r.cree desc limit 15)
        union all
        (select 'export', -e.prix, case when e.inclus then 'inclus' when e.offert then 'offert' else 'valide' end, e.periode, e.nb_operations::text,
                (extract(epoch from e.cree) * 1000)::bigint
           from public.export e where e.user_id = auth.uid() order by e.cree desc limit 15)
        union all
        (select 'module', -pm.montant, pm.nature, coalesce(m.nom, pm.code), pm.code,
                (extract(epoch from pm.cree) * 1000)::bigint
           from public.paiement_module pm left join public.module m on m.code = pm.code
          where pm.user_id = auth.uid() order by pm.cree desc limit 15)) h),
    'serveur', (extract(epoch from now()) * 1000)::bigint);
end $$;

-- ---------- Superadmin ----------

create or replace function public.admin_modules() returns json
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return json_build_object(
    'catalogue', (select coalesce(json_agg(json_build_object('code', m.code, 'nom', m.nom, 'description', m.description, 'prix', m.prix,
        'par_telephone', m.par_telephone, 'est_pack', m.est_pack, 'contenu', m.contenu, 'telephones', m.telephones, 'actif', m.actif,
        'abonnes', (select count(*) from public.abonnement a where a.code = m.code and a.fin > now()),
        'essais', (select count(*) from public.abonnement a where a.code = m.code and a.fin > now()
                     and exists (select 1 from public.paiement_module p where p.user_id = a.user_id and p.code = a.code and p.nature = 'essai' and p.fin = a.fin)),
        'revenu_mois', (select coalesce(sum(p.montant), 0) from public.paiement_module p where p.code = m.code and p.cree >= date_trunc('month', now())))
        order by m.est_pack desc, m.ordre), '[]'::json) from public.module m),
    'revenu_mois', (select coalesce(sum(montant), 0) from public.paiement_module where cree >= date_trunc('month', now())),
    'comptes', (select coalesce(json_agg(json_build_object('user_id', u.user_id, 'actifs', public.modules_actifs(u.user_id),
        'fin', (select min(fin) from public.abonnement a where a.user_id = u.user_id and a.fin > now()))), '[]'::json)
      from public.profil u));
end $$;

create or replace function public.admin_module(p_code text, p_prix integer, p_actif boolean) returns void
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  if p_prix is null or p_prix < 0 or p_prix > 1000000 then raise exception 'Prix invalide.'; end if;
  update public.module set prix = p_prix, actif = coalesce(p_actif, actif) where code = p_code;
  if not found then raise exception 'Application inconnue.'; end if;
end $$;

-- Offrir des jours d'une application ou d'un pack à un compte (geste commercial).
create or replace function public.admin_offrir_module(p_user uuid, p_code text, p_jours integer, p_motif text) returns void
language plpgsql security definer set search_path = public as $$
declare v_debut timestamptz; v_fin timestamptz;
begin
  perform public.verifier_admin();
  if p_jours is null or p_jours < 1 or p_jours > 366 then raise exception 'Nombre de jours invalide.'; end if;
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
end $$;

-- ---------- Lancement : un mois offert de toutes les applications aux comptes déjà inscrits ----------
do $$
begin
  if not exists (select 1 from public.parametre where cle = 'lancement_modules') then
    insert into public.abonnement (user_id, code, fin, renouveler, essai_utilise, offert)
    select p.user_id, m.code, now() + interval '1 month', false, true, true
      from public.profil p cross join public.module m
     where not m.est_pack
    on conflict (user_id, code) do nothing;
    insert into public.paiement_module (user_id, code, montant, nature, debut, fin, motif)
    select p.user_id, m.code, 0, 'offert', now(), now() + interval '1 month', 'Lancement des applications : 1 mois offert'
      from public.profil p cross join public.module m where not m.est_pack;
    insert into public.parametre (cle, valeur) values ('lancement_modules', to_char(now(), 'YYYY-MM-DD'));
  end if;
end $$;

-- ---------- Droits ----------
do $$
declare f text;
begin
  foreach f in array array['mes_modules()', 'activer_module(text)', 'arreter_module(text)', 'admin_modules()',
                           'admin_module(text, integer, boolean)', 'admin_offrir_module(uuid, text, integer, text)',
                           'payer_export(uuid, integer, text, text, bigint, boolean)', 'creer_caisse(text, text, text)',
                           'mon_portefeuille()'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to authenticated', f);
  end loop;
  foreach f in array array['modules_actifs(uuid)', 'pack_actif(uuid, text)', 'prix_module(uuid, text)', 'renouveler_modules(uuid)'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
  end loop;
end $$;
