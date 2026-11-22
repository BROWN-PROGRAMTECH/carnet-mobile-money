-- Carnet Mobile Money · version 0.12 : application « Alertes WhatsApp ».
-- À exécuter UNE fois dans Supabase → SQL Editor, après modules.sql. Peut être relancé sans danger.
--
-- Le téléphone de chaque caisse prépare ses alertes (bilan du soir, écart de caisse, SMS manquant, solde bas,
-- échéances de dettes) et les dépose ici (signaler_alerte). La fonction Edge « whatsapp » les envoie par
-- l'API WhatsApp Business de Meta avec des modèles validés. Garde-fous :
--  - application « Alertes WhatsApp » active ; type d'alerte activé dans les réglages ;
--  - destinataires confirmés par un code reçu sur WhatsApp (numéro du compte + 2 autres au plus) ;
--  - une même alerte (clé) n'est jamais envoyée deux fois au même numéro ; 30 messages par jour et par compte ;
--  - une alerte non envoyée en 24 heures expire (compte Meta pas encore prêt, numéro injoignable…).

-- ---------- Application ----------
insert into public.module (code, nom, description, prix, ordre) values
  ('ALERTES', 'Alertes WhatsApp', 'Bilan du soir, écart de caisse, SMS manquant, solde bas et échéances de dettes envoyés sur WhatsApp au patron et à 2 numéros.', 200, 60)
on conflict (code) do nothing;
update public.module set contenu = array_append(contenu, 'ALERTES'),
       description = 'Toutes les applications, jusqu''à 3 téléphones.'
 where code = 'PACK_ENTREPRISE' and not ('ALERTES' = any (contenu));

-- ---------- Tables (aucun accès direct des clients : tout passe par les fonctions) ----------
create table if not exists public.destinataire (
  user_id uuid not null references auth.users (id) on delete cascade,
  numero text not null check (numero ~ '^[0-9]{9}$'),
  nom text not null default '',
  confirme boolean not null default false,
  code_hash text,
  code_expire timestamptz,
  essais integer not null default 0,
  dernier_code timestamptz,
  cree timestamptz not null default now(),
  primary key (user_id, numero)
);

create table if not exists public.alerte_pref (
  user_id uuid primary key references auth.users (id) on delete cascade,
  bilan boolean not null default true,
  bilan_heure integer not null default 20 check (bilan_heure between 0 and 23),
  ecart boolean not null default true,
  chaine boolean not null default true,
  solde boolean not null default true,
  seuil_om integer not null default 5000,
  seuil_momo integer not null default 5000,
  dettes boolean not null default true,
  maj timestamptz not null default now()
);

create table if not exists public.whatsapp_envoi (
  id bigserial primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  numero text not null,
  type text not null,
  modele text not null,
  params jsonb not null default '[]',
  cle text not null,
  statut text not null default 'en_attente' check (statut in ('en_attente', 'envoye', 'echec', 'expire')),
  essais integer not null default 0,
  erreur text,
  wamid text,
  cree timestamptz not null default now(),
  envoye timestamptz,
  unique (user_id, numero, cle)
);
create index if not exists whatsapp_envoi_attente on public.whatsapp_envoi (cree) where statut = 'en_attente';
create index if not exists whatsapp_envoi_user on public.whatsapp_envoi (user_id, cree);

alter table public.destinataire enable row level security;
alter table public.alerte_pref enable row level security;
alter table public.whatsapp_envoi enable row level security;
revoke all on public.destinataire, public.alerte_pref, public.whatsapp_envoi from anon, authenticated;

create or replace function public.hash_code(p_user uuid, p_numero text, p_code text) returns text
language sql immutable set search_path = public, extensions as $$
  select encode(extensions.digest(p_user::text || '|' || p_numero || '|' || p_code, 'sha256'), 'hex')
$$;

-- ---------- Fonctions du client ----------

create or replace function public.mes_alertes() returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'Non connecté.'; end if;
  insert into public.alerte_pref (user_id) values (v_uid) on conflict (user_id) do nothing;
  return json_build_object(
    'destinataires', (select coalesce(json_agg(json_build_object('numero', d.numero, 'nom', d.nom, 'confirme', d.confirme) order by d.cree), '[]'::json)
                        from public.destinataire d where d.user_id = v_uid),
    'prefs', (select row_to_json(p) from (select bilan, bilan_heure, ecart, chaine, solde, seuil_om, seuil_momo, dettes
                                            from public.alerte_pref where user_id = v_uid) p),
    'envois_jour', (select count(*) from public.whatsapp_envoi where user_id = v_uid and cree > now() - interval '24 hours'),
    'recents', (select coalesce(json_agg(json_build_object('type', e.type, 'numero', e.numero, 'statut', e.statut, 'erreur', e.erreur,
                    'quand', (extract(epoch from e.cree) * 1000)::bigint) order by e.cree desc), '[]'::json)
                  from (select * from public.whatsapp_envoi where user_id = v_uid order by cree desc limit 10) e),
    'actif', 'ALERTES' = any (public.modules_actifs(v_uid)));
end $$;

create or replace function public.regler_alertes(p json) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  insert into public.alerte_pref (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  update public.alerte_pref set
    bilan = coalesce((p->>'bilan')::boolean, bilan),
    bilan_heure = least(23, greatest(0, coalesce((p->>'bilan_heure')::integer, bilan_heure))),
    ecart = coalesce((p->>'ecart')::boolean, ecart),
    chaine = coalesce((p->>'chaine')::boolean, chaine),
    solde = coalesce((p->>'solde')::boolean, solde),
    seuil_om = least(10000000, greatest(0, coalesce((p->>'seuil_om')::integer, seuil_om))),
    seuil_momo = least(10000000, greatest(0, coalesce((p->>'seuil_momo')::integer, seuil_momo))),
    dettes = coalesce((p->>'dettes')::boolean, dettes),
    maj = now()
  where user_id = auth.uid();
end $$;

create or replace function public.retirer_destinataire(p_numero text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  delete from public.destinataire where user_id = auth.uid() and numero = right(regexp_replace(coalesce(p_numero, ''), '\D', '', 'g'), 9);
end $$;

create or replace function public.confirmer_destinataire(p_numero text, p_code text) returns json
language plpgsql security definer set search_path = public as $$
declare v_num text := right(regexp_replace(coalesce(p_numero, ''), '\D', '', 'g'), 9); d public.destinataire;
begin
  if auth.uid() is null then raise exception 'Non connecté.'; end if;
  select * into d from public.destinataire where user_id = auth.uid() and numero = v_num for update;
  if not found then raise exception 'Numéro inconnu : demandez d''abord un code.'; end if;
  if d.confirme then return json_build_object('ok', true); end if;
  if d.essais >= 5 then raise exception 'Trop d''essais : demandez un nouveau code.'; end if;
  if d.code_expire is null or d.code_expire < now() then raise exception 'Code expiré : demandez-en un nouveau.'; end if;
  if d.code_hash <> public.hash_code(auth.uid(), v_num, regexp_replace(coalesce(p_code, ''), '\D', '', 'g')) then
    update public.destinataire set essais = essais + 1 where user_id = auth.uid() and numero = v_num;
    raise exception 'Code incorrect.';
  end if;
  update public.destinataire set confirme = true, code_hash = null, code_expire = null, essais = 0
   where user_id = auth.uid() and numero = v_num;
  return json_build_object('ok', true);
end $$;

-- Dépose une alerte pour tous les destinataires confirmés. p_params : tableau de textes (paramètres du modèle).
create or replace function public.signaler_alerte(p_type text, p_cle text, p_params json) returns json
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); pr public.alerte_pref; v_modele text; v_params jsonb := '[]'; e json; v_txt text;
        v_n integer := 0; v_c integer; v_jour integer; d record;
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
  -- WhatsApp refuse les retours à la ligne, tabulations et plus de 4 espaces de suite dans un paramètre.
  for e in select * from json_array_elements(p_params) loop
    v_txt := left(regexp_replace(regexp_replace(coalesce(e #>> '{}', ''), '[\r\n\t]+', ' ', 'g'), ' {4,}', '   ', 'g'), 400);
    if btrim(v_txt) = '' then v_txt := '-'; end if;
    v_params := v_params || to_jsonb(v_txt);
  end loop;
  select count(*) into v_jour from public.whatsapp_envoi where user_id = v_uid and cree > now() - interval '24 hours';
  for d in select numero from public.destinataire where user_id = v_uid and confirme order by cree loop
    if v_jour + v_n >= 30 then exit; end if;
    insert into public.whatsapp_envoi (user_id, numero, type, modele, params, cle)
    values (v_uid, d.numero, p_type, v_modele, v_params, p_cle)
    on conflict (user_id, numero, cle) do nothing;
    get diagnostics v_c = row_count;
    v_n := v_n + v_c;
  end loop;
  return json_build_object('ok', true, 'deposees', v_n);
end $$;

-- ---------- Fonctions réservées à la fonction Edge (clé de service) ----------

-- Prépare un code de confirmation pour un nouveau destinataire ; renvoie le code à envoyer par WhatsApp.
create or replace function public.preparer_code(p_user uuid, p_numero text, p_nom text) returns text
language plpgsql security definer set search_path = public as $$
declare v_num text := right(regexp_replace(coalesce(p_numero, ''), '\D', '', 'g'), 9); v_code text; d public.destinataire;
begin
  if v_num !~ '^6[0-9]{8}$' then raise exception 'Numéro camerounais invalide (6XXXXXXXX).'; end if;
  if not ('ALERTES' = any (public.modules_actifs(p_user))) then raise exception 'Activez d''abord l''application Alertes WhatsApp.'; end if;
  select * into d from public.destinataire where user_id = p_user and numero = v_num for update;
  if not found and (select count(*) from public.destinataire where user_id = p_user) >= 3 then
    raise exception 'Trois numéros au plus : retirez-en un d''abord.';
  end if;
  if found and d.dernier_code > now() - interval '1 minute' then raise exception 'Patientez une minute avant de redemander un code.'; end if;
  v_code := lpad((floor(random() * 1000000))::integer::text, 6, '0');
  insert into public.destinataire (user_id, numero, nom, confirme, code_hash, code_expire, essais, dernier_code)
  values (p_user, v_num, left(coalesce(p_nom, ''), 40), false, public.hash_code(p_user, v_num, v_code), now() + interval '10 minutes', 0, now())
  on conflict (user_id, numero) do update set nom = case when excluded.nom <> '' then excluded.nom else public.destinataire.nom end,
    code_hash = excluded.code_hash, code_expire = excluded.code_expire, essais = 0, dernier_code = now();
  return v_code;
end $$;

-- Alertes à envoyer (les plus anciennes d'abord) ; celles de plus de 24 heures expirent.
create or replace function public.envois_a_faire(p_limite integer default 50) returns table (id bigint, numero text, modele text, params jsonb)
language plpgsql security definer set search_path = public as $$
begin
  update public.whatsapp_envoi set statut = 'expire', erreur = coalesce(erreur, 'Non envoyée en 24 heures')
   where statut = 'en_attente' and cree < now() - interval '24 hours';
  return query select e.id, e.numero, e.modele, e.params from public.whatsapp_envoi e
    where e.statut = 'en_attente' and e.essais < 5 order by e.cree limit greatest(1, least(200, coalesce(p_limite, 50)));
end $$;

create or replace function public.marquer_envoi(p_id bigint, p_ok boolean, p_wamid text, p_erreur text) returns void
language plpgsql security definer set search_path = public as $$
begin
  update public.whatsapp_envoi set
    statut = case when p_ok then 'envoye' when essais + 1 >= 5 then 'echec' else 'en_attente' end,
    essais = essais + 1, wamid = p_wamid, erreur = left(p_erreur, 300), envoye = case when p_ok then now() end
  where id = p_id;
end $$;

-- ---------- Superadmin : activité des alertes ----------
create or replace function public.admin_alertes() returns json
language plpgsql security definer set search_path = public as $$
begin
  perform public.verifier_admin();
  return json_build_object(
    'jour', (select json_object_agg(statut, n) from (select statut, count(*) n from public.whatsapp_envoi where cree > now() - interval '24 hours' group by statut) x),
    'mois', (select count(*) from public.whatsapp_envoi where statut = 'envoye' and envoye >= date_trunc('month', now())),
    'destinataires', (select count(*) from public.destinataire where confirme),
    'erreurs', (select coalesce(json_agg(json_build_object('quand', cree, 'type', type, 'erreur', erreur) order by cree desc), '[]'::json)
                  from (select * from public.whatsapp_envoi where statut in ('echec', 'expire') or erreur is not null order by cree desc limit 20) z));
end $$;

-- ---------- Droits ----------
do $$
declare f text;
begin
  foreach f in array array['mes_alertes()', 'regler_alertes(json)', 'retirer_destinataire(text)', 'confirmer_destinataire(text, text)',
                           'signaler_alerte(text, text, json)', 'admin_alertes()'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to authenticated', f);
  end loop;
  foreach f in array array['preparer_code(uuid, text, text)', 'envois_a_faire(integer)', 'marquer_envoi(bigint, boolean, text, text)',
                           'hash_code(uuid, text, text)'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to service_role', f);
  end loop;
end $$;
