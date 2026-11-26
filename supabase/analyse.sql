-- Carnet Mobile Money · version 0.16 : application « Analyse ».
-- À exécuter UNE fois dans Supabase → SQL Editor, après promotions.sql. Peut être relancé sans danger.
-- Les analyses sont calculées sur le téléphone : le serveur ne gère que l'abonnement (300 FCFA/mois, 14 jours d'essai),
-- et l'application est ajoutée au Pack Commerçant et au Pack Entreprise.

insert into public.module (code, nom, description, prix, ordre) values
  ('ANALYSE', 'Analyse', 'Évolution sur 12 mois, meilleurs jours et heures de vente, clients fidèles et perdus, où part l''argent, dépenses inhabituelles, prévisions de fin de mois, budgets proposés et suivis.', 300, 25)
on conflict (code) do nothing;
update public.module set contenu = array_append(contenu, 'ANALYSE')
 where code in ('PACK_COMMERCANT', 'PACK_ENTREPRISE') and not ('ANALYSE' = any (contenu));
