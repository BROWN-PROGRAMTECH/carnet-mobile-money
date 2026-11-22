# Activer l'envoi WhatsApp (compte Meta) · Carnet Mobile Money 0.12

À faire une seule fois par le superadmin. Tant que ce n'est pas fait, les alertes attendent puis expirent au bout de 24 heures.

## 1. Compte et numéro
1. Sur **business.facebook.com**, créez un portefeuille d'entreprise (Meta Business) au nom de l'activité.
2. Sur **developers.facebook.com** → Mes applications → Créer une application → type **Entreprise**, puis ajoutez le produit **WhatsApp**.
3. WhatsApp → **Configuration de l'API** → ajoutez le **numéro dédié** :
   - il doit recevoir un SMS ou un appel pour être vérifié ;
   - il ne doit **pas** être utilisé dans l'application WhatsApp ;
   - nom affiché : « Carnet Mobile Money ».
4. WhatsApp Manager → **Facturation** : ajoutez un moyen de paiement. C'est obligatoire pour les messages utilitaires, facturés quelques FCFA chacun au tarif « Reste de l'Afrique ».
5. (Conseillé) Faites vérifier l'entreprise pour lever les limites d'envoi.

## 2. Jeton permanent
1. Paramètres de l'entreprise → Utilisateurs → **Utilisateurs système** → Ajouter (rôle Admin).
2. Attribuez-lui l'application et le compte WhatsApp (contrôle total).
3. **Générer un jeton** avec les autorisations `whatsapp_business_messaging` et `whatsapp_business_management`, sans expiration. Copiez-le.
4. Dans WhatsApp → Configuration de l'API, copiez l'**identifiant du numéro de téléphone** (*Phone number ID*).

## 3. Les 5 modèles (WhatsApp Manager → Modèles de messages, langue : Français)
| Nom exact | Catégorie | Texte |
|---|---|---|
| `bilan_du_soir` | Utilitaire | Bilan du {{1}} – {{2}}<br>Ventes : {{3}}<br>Dépenses : {{4}}<br>Résultat : {{5}}<br>Soldes : {{6}}<br>À classer : {{7}}<br>Carnet Mobile Money |
| `ecart_caisse` | Utilitaire | Écart de caisse – {{1}}, le {{2}}. Compté : {{3}}. Attendu : {{4}}. Écart : {{5}}. Vérifiez la caisse avec la personne de garde. |
| `alerte_compte` | Utilitaire | Alerte {{1}} – {{2}} : {{3}}<br>Carnet Mobile Money |
| `echeances_dettes` | Utilitaire | Remboursements du {{1}} : {{2}}<br>Carnet Mobile Money |
| `code_verification` | Authentification | (texte fourni par Meta) + bouton « Copier le code » |

Exemples à donner à Meta pour la validation :
- **bilan_du_soir** : 22/11/2026, Boutique Marché, 85 000 FCFA, 42 000 FCFA, +43 000 FCFA, Orange Money 120 000 · MTN MoMo 35 000 · Espèces 18 000, 2 opérations.
- **ecart_caisse** : Boutique Marché, 22/11/2026, 17 500 FCFA, 18 000 FCFA, −500 FCFA.
- **alerte_compte** : Orange Money, Boutique Marché, Solde bas : 3 200 FCFA (seuil 5 000 FCFA). Pensez à recharger.
- **echeances_dettes** : 22/11/2026, Paul vous doit 15 000 FCFA (aujourd'hui).

## 4. Brancher sur Supabase
Supabase → **Edge Functions → Secrets** :
- `WHATSAPP_TOKEN` : le jeton permanent ;
- `WHATSAPP_PHONE_ID` : l'identifiant du numéro.

C'est tout : la fonction `whatsapp` est déjà déployée. Pour essayer :
1. Dans l'application : Compte → Alertes WhatsApp → Ajouter un numéro. Le code arrive sur WhatsApp.
2. Puis « Envoyer le bilan maintenant ».
3. Le tableau de bord (onglet Applications) montre les envois et les erreurs.
