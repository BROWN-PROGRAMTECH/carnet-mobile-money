# Carnet Mobile Money — prototype Android

Application Android qui lit directement les SMS Orange Money et MTN MoMo du téléphone, sans export.

## Version 0.9.1 : vos catégories, choix par recherche, recharge simplifiée

**Catégories personnalisées** (communes à tous les téléphones du compte) :
- Créez vos catégories : **Recette (+)** pour les entrées (ex. Commission, Prestation), **Charge (−)** pour les sorties (rangée sous Charges, avec budget possible, ex. Publicité), ou **Hors résultat** (ex. Cotisation, Avance) pour les entrées, les sorties ou les deux.
- **Masquez** les catégories fournies que vous n'utilisez jamais (sauf Autre et Opération client) : elles disparaissent des choix, l'historique reste intact.
- Gestion dans **Compte → Catégories** : créer, renommer, masquer ou réafficher.

**Choix de la catégorie** : un champ « Rechercher ou créer une catégorie… », vos 3 catégories les plus utilisées en haut, puis la liste par sections (Entrées / Sorties / Charges / Mes catégories / Entrée ou sortie). Si le mot n'existe pas : « + Créer la catégorie » directement. Les boutons rapides de la page À classer reprennent vos 3 catégories les plus utilisées.

**Recharge du porte-monnaie en 2 étapes** :
1. **Payer** : l'écran montre seulement le numéro, le nom affiché par l'opérateur et le bouton « J'ai envoyé l'argent ».
2. **Déclarer** : l'application retrouve elle-même le SMS de confirmation du transfert vers le numéro de recharge (montant et ID) et propose « Déclarer cette recharge » en un toucher. Saisie manuelle de l'ID possible en secours (paiement fait depuis un autre téléphone).

Numéros de recharge enregistrés : Orange Money 657 268 549, MTN MoMo 653 877 731, au nom de BROWN ALDEN DJOMO.

> Serveur déjà à jour (`supabase/categories.sql` appliqué).

## Version 0.9 : gestion des finances et SMS des banques

**Nouvel onglet Finances** (barre du bas : Caisse · À classer · Finances · Exporter · Compte), mois par mois (◂ ▸) :
- **Trésorerie d'aujourd'hui** : dernier solde de chaque compte (OM, MoMo, banques lues par SMS), espèces du jour et **autres avoirs** saisis à la main (banque sans SMS, épargne, tontine…), avec l'évolution des soldes sur 30 jours.
- **Le mois** : ventes, achats, charges, frais et résultat, avec la variation par rapport à la même période du mois précédent ; apports et retraits personnels ; ce que l'activité garde après vos retraits.
- **Dépenses par poste et budgets** : budget mensuel par poste, barres de progression, alertes à 80 % et 100 %.
- **Ventes par moyen de paiement** et **meilleurs clients** du mois.

**Postes de charges** : la catégorie « Charge » se détaille en Loyer, Salaires, Transport, Électricité / eau, Téléphone / internet, Impôts et taxes, Autre charge. Les charges déjà classées deviennent « Autre charge ». Le choix de catégorie est présenté par sections (Sorties, Charges, Entrée ou sortie).

**SMS des banques** : Afriland First Bank, UBA, CCA Bank, Ecobank, SG Cameroun, SCB, BICEC, CBC, BGFIBank, Banque Atlantique, Access Bank, UBC, NFC Bank, Citibank (liste modifiable dans Compte → Réglages avancés).
- Avis de solde (format Afriland vérifié sur de vrais SMS), débits et crédits avec montant et solde (français et anglais).
- La publicité des banques est ignorée ; un SMS bancaire dont le sens n'est pas clair va dans « À vérifier ».
- Chaque banque apparaît comme un compte, avec contrôle des soldes comme OM et MoMo.
- À la première ouverture de la 0.9, la messagerie est relue une fois pour retrouver les anciens SMS des banques.

**Export Excel** : nouvelle feuille « Finances » (trésorerie, dépenses par poste face au budget, ventes par moyen, meilleurs clients). L'export « Toutes les caisses » ajoute la trésorerie de chaque caisse.

> Serveur déjà à jour (`supabase/finances.sql` appliqué). Pour un autre projet : SQL Editor → collez `supabase/finances.sql` → Run.
>
> Formats bancaires : seuls les avis de solde Afriland ont été vérifiés sur de vrais SMS. Si un SMS de votre banque n'est pas bien lu, il apparaît dans « À vérifier » : envoyez-en un exemple (montants et numéros masqués) pour ajouter une règle précise.

## Version 0.8 : un compte, plusieurs téléphones, une caisse par téléphone

- **Un seul compte** (un numéro, un code secret, un porte-monnaie) pour tous les téléphones d'un commerçant.
- **Chaque téléphone a sa caisse** : ses SMS, ses soldes OM et MoMo, ses espèces et ses classements ne se mélangent plus avec ceux des autres téléphones. Les règles « toujours classer ce contact » restent communes.
- **Premier téléphone** : sa caisse « Caisse principale » est créée automatiquement (renommable dans Compte).
- **Autre téléphone** : après connexion, onglet Compte → « Créer la caisse de ce téléphone » (cas habituel) ou « Reprendre » la caisse d'un ancien téléphone (remplacement, perte).
- **Code patron** (4 à 6 chiffres, onglet Compte) : protège la vue « Toutes mes caisses » et la reprise d'une caisse. 5 erreurs = blocage 15 minutes. Il n'est jamais enregistré en clair.
- **Toutes mes caisses** (onglet Caisse, dès 2 caisses) : résultat total, puis une carte par caisse (résultat, ventes, achats + charges, frais, opérations, derniers soldes, dernière sauvegarde). Chaque téléphone envoie un résumé par jour, sans SMS ni nom de contact.
- **Export « Toutes les caisses »** (onglet Exporter) : une ligne par caisse + le détail par jour, même tarif que l'export d'une caisse.
- **Comptes déjà utilisés sur plusieurs téléphones** : à la mise à jour, chaque téléphone garde les SMS présents dans sa messagerie ; ceux reçus de l'autre téléphone sont mis de côté (jamais effacés, récupérables dans Compte).

> Le serveur est déjà à jour (`supabase/caisses.sql` appliqué sur votre projet). Pour un autre projet : SQL Editor → collez `supabase/caisses.sql` → Run.
>
> Le code patron est une protection de l'application : quelqu'un qui connaît le numéro et le code secret du compte reste propriétaire des données. Ne donnez pas le code secret du compte à un employé : saisissez-le vous-même sur son téléphone.

## Version 0.7 : catégories selon le sens (entrée / sortie)

Chaque opération ne propose que les catégories qui vont avec son sens.

| Entrées uniquement | Sorties uniquement | Entrées et sorties |
|---|---|---|
| Vente | Achat / fournisseur | Don |
| Apport personnel | Charge (loyer, salaire, transport…) | Transfert entre mes comptes |
| Prêt reçu | Retrait personnel | Autre |
| | Remboursement de prêt | Opération client (agent, automatique) |

- **Résultat = Ventes − Achats − Charges − Frais.** Apports, retraits personnels, prêts, dons et transferts n'en font pas partie.
- **Conversion automatique** des anciennes catégories : Paiement → Achat / fournisseur ; Personnel → Apport (entrée) ou Retrait personnel (sortie) ; une Vente sur une sortie (ou un Paiement sur une entrée) revient « À classer ». Rien n'est effacé.
- **Toujours classer un contact** : la règle est maintenant séparée pour les entrées et les sorties. Un même numéro peut être client quand il vous paie et fournisseur quand vous le payez. Les anciennes règles continuent de s'appliquer, converties selon le sens.
- Page À classer : 3 boutons rapides selon le sens (entrée : Vente, Apport perso, Transfert ; sortie : Achat, Charge, Retrait perso) + « Autres ▸ ».
- Fenêtre de classement et saisie d'espèces : la liste change avec le sens, et une phrase explique si la catégorie compte dans le résultat.
- Excel, feuille Synthèse : colonnes « Type » (entrée / sortie / les deux) et « Dans le résultat » (+, −, non).

## Version 0.6.2 : correction de la fermeture au démarrage

- L'application se fermait dès l'ouverture (affichage de l'état en haut de l'écran qui s'appelait lui-même en boucle). Corrigé.
- `CloudConfig.java` est désormais inclus dans le zip, avec les valeurs du projet Supabase.

## Version 0.6.1 : protection contre les fermetures

- Si une page ne peut pas s'afficher, l'application reste ouverte et affiche l'erreur au lieu de se fermer.
- Après une fermeture inattendue, un écran montre le détail au lancement suivant, avec le bouton « Rouvrir l'application ».
- Le détail technique (jamais de SMS ni de données de caisse) est envoyé au support avec la sauvegarde ; il apparaît dans l'onglet Incidents du tableau de bord.

## Nouveautés de la version 0.6 : export payant, porte-monnaie et nouvelle interface

**Modèle économique**
- L'application est **gratuite** : lecture des SMS, caisse, classement, espèces, sauvegarde.
- Seul l'**export Excel** est payant, **à chaque fichier** : 1 à 200 opérations = **500 FCFA**, plus de 200 = **1 000 FCFA** (réglable dans le tableau de bord). **Le premier export est offert.**
- **Porte-monnaie prépayé** : le client envoie par exemple 5 000 FCFA par Orange Money ou MoMo à votre numéro, recopie l'ID de transaction dans l'application, vous validez une fois, puis chaque export se débite **instantanément**.
- **Sans réseau**, l'export passe sur le dernier solde connu ; le débit est envoyé au retour du réseau (un même export n'est jamais débité deux fois).
- Le montant n'est débité qu'au moment où le fichier est enregistré ; si le débit est refusé, le fichier vide est supprimé.
- L'export Excel nécessite un compte (gratuit). Les exports CSV et « SMS anonymisés » sont retirés.

**Interface**
- **Barre de navigation fixe en bas** : Caisse · À classer (pastille avec le nombre) · Exporter · Compte. Plus de menu « Plus… ».
- **Plus de messages flottants** : un bandeau dans l'écran, que l'on touche pour fermer.
- Caisse : grande carte « Résultat », cartes Orange Money / MoMo côte à côte (touchez pour le détail), carte Espèces avec ses boutons, puis Journal / Espèces / À vérifier.
- Exporter : nombre d'opérations, prix, solde après export, recharge guidée en 3 étapes, historique.
- Compte : connexion, sauvegarde, réglages de la caisse et de la lecture des SMS au même endroit.

**Correction importante** : la sauvegarde en ligne s'arrêtait après 500 SMS (les SMS lus en une fois ont la même date de modification). Elle reprend maintenant exactement où elle s'était arrêtée, et **les SMS manquants partent tout seuls** à la prochaine sauvegarde.

### Mise en place

1. **Serveur** : le fichier `supabase/portefeuille.sql` **a déjà été exécuté sur votre projet** `carnet-mobile-money`. Pour un autre projet : SQL Editor → collez-le → Run.
2. **Devenir superadmin** (une fois) : SQL Editor →
   ```sql
   insert into public.admin (user_id)
   select id from auth.users where email = '2376XXXXXXXX@carnet-mm.app';
   ```
3. **Tableau de bord** : GitHub → Settings → Pages → Deploy from a branch → main, dossier `/docs` → Save. Ouvrez `https://<votre-compte>.github.io/<dépôt>/admin/`. L'adresse et la clé publique du projet sont déjà dans la page.
4. Onglet **Paramètres** : saisissez vos numéros Orange Money et MoMo qui reçoivent les recharges (sinon l'écran Recharger dit « numéros pas encore disponibles »).

> Le fichier `CloudConfig.java` n'est **pas** dans ce zip : gardez celui du dépôt, qui contient déjà vos valeurs.
> Supprimez du dépôt `supabase/abonnement.sql` et `app/src/main/java/cm/carnet/mobilemoney/Licence.java` s'ils y sont (la version 0.5 n'est plus utilisée).

## Nouveautés de la version 0.4.1 : ouverture rapide

- **Ouverture immédiate** : la caisse s'affiche tout de suite à partir des données déjà enregistrées dans l'application.
- **Seuls les nouveaux SMS sont lus** : l'application retient jusqu'où elle a lu la messagerie et ne parcourt plus que les SMS arrivés depuis, et uniquement ceux des expéditeurs Orange Money et MoMo.
- **Lecture mémorisée** : chaque SMS compris une fois n'est plus jamais relu ; il est relu seulement si les règles de lecture évoluent ou si le numéro titulaire change.
- **Première lecture complète une seule fois** (après installation, ou après un changement d'expéditeurs dans les Réglages).
- Réglages → « Relire / effacer… » : relire toute la messagerie si des SMS anciens manquent, ou effacer la copie et tout relire.
- Mesure sur 10 fois la sauvegarde de test (3 390 SMS mobile money) : analyse complète 0,3 à 0,7 s, réouverture 0,05 à 0,1 s sur ordinateur, résultats identiques.

## Nouveautés de la version 0.4 : compte et sauvegarde en ligne

- **Compte** : numéro de téléphone + code secret (6 caractères minimum). Aucun SMS ni e-mail n'est envoyé.
- **Sauvegarde automatique** : opérations, catégories, motifs, règles par contact, espèces et clôtures, dès qu'il y a du réseau (peu après chaque nouveau SMS, puis toutes les heures, et à l'ouverture de l'application).
- **Restauration** : sur un nouveau téléphone, connectez-vous avec le même numéro et le même code secret ; tout revient et se fusionne avec ce qui est déjà sur le téléphone.
- **Fonctionne hors ligne** : rien ne bloque sans réseau ; la sauvegarde reprend toute seule au retour du réseau.
- **Sans compte**, l'application reste 100 % locale, comme avant.
- Ligne « ☁ » en haut de l'écran : état de la dernière sauvegarde ; menu « Plus… → Compte et sauvegarde ».

**Ne jamais utiliser comme code secret le code Orange Money ou MoMo.**

### Configurer le serveur Supabase (une seule fois, environ 15 minutes)

1. **Créer le projet** : sur https://supabase.com, créez un compte (« Continue with GitHub » fonctionne), puis **New project**.
   - Nom : `carnet-mobile-money`. Mot de passe de la base : générez-le et **notez-le**.
   - Région : choisissez une région d'Europe de l'Ouest (Paris ou Francfort), la plus proche du Cameroun.
   - Plan : **Free** pour commencer.
2. **Créer les tables** : menu **SQL Editor → New query**, collez tout le fichier `supabase/schema.sql`, puis **Run**. Le message attendu est « Success. No rows returned ».
3. **Connexion sans e-mail** : menu **Authentication → Sign In / Providers → Email**. Laissez Email activé et **désactivez « Confirm email »**, puis enregistrez. Sans ce réglage, la création de compte échoue (l'application vous le dira).
4. **Récupérer les deux valeurs** : menu **Project Settings → API Keys** (ou **API** sur les anciens projets).
   - **Project URL** : `https://xxxxxxxx.supabase.co` (Project Settings → Data API sur les nouveaux projets).
   - **Clé publique** : « publishable » (`sb_publishable_…`) ou « anon public ».
   - **Ne copiez jamais la clé secrète** (`sb_secret_…` ou « service_role »).
5. **Les mettre dans l'application** : dans votre dépôt GitHub, ouvrez `app/src/main/java/cm/carnet/mobilemoney/CloudConfig.java`, touchez le crayon (Edit), remplissez :
   ```java
   public static final String URL = "https://xxxxxxxx.supabase.co";
   public static final String ANON_KEY = "sb_publishable_xxxxxxxx";
   ```
   puis **Commit changes**. GitHub compile le nouvel APK (onglet Actions, puis Releases).
6. **Dans l'application** : touchez la ligne « ☁ … » en haut, saisissez votre numéro et un code secret, puis **Créer mon compte**.

### À savoir

- **Projet gratuit en pause** : Supabase met en pause un projet gratuit après 7 jours de faible activité ; il se réactive depuis le tableau de bord (**Resume project**). Une caisse utilisée chaque jour le garde actif.
- **Clé publique dans un dépôt public** : elle est prévue pour être distribuée ; la sécurité vient des règles d'accès par ligne. En revanche, n'importe qui la connaissant peut créer un compte sur votre projet ; l'abonnement (version suivante) encadrera l'accès.
- **Données personnelles** : les SMS sauvegardés contiennent des noms et numéros de clients. Ils sont chiffrés en transit (HTTPS) et isolés par compte. Hébergement hors du Cameroun : prévoir la déclaration ou l'autorisation auprès de l'APDP (loi n° 2024/017) avant la commercialisation.
- **Changer de compte** sur un même téléphone ajoute les données du téléphone au nouveau compte : se déconnecter n'efface rien localement.

## Nouveautés de la version 0.3 : la caisse automatique

- **La caisse se remplit toute seule** : chaque opération lue dans un SMS Orange Money ou MoMo devient une ligne de caisse. Le montant vient du SMS et **ne se modifie pas**.
- **Catégorie** pour chaque ligne : Vente, Paiement, Don, Personnel ou Autre. Tant qu'elle n'est pas choisie, la ligne reste « À classer ».
- **Onglet « À classer »** : un toucher sur la catégorie sous chaque opération suffit.
- **Motif ajustable** : touchez une opération pour modifier son motif (prérempli avec le message du SMS quand il existe).
- **« Toujours classer ce contact ainsi »** : un fournisseur, un client régulier ou un proche est classé automatiquement les fois suivantes.
- **Caisse de la période** : ventes − paiements − frais = résultat ; dons et mouvements personnels affichés à part ; alerte tant qu'il reste des opérations à classer.
- **Espèces** (sans SMS, donc saisies à la main) : fond de caisse, entrées et sorties avec catégorie, comptage à la clôture, écart. Un mouvement s'annule mais ne s'efface pas.
- **Mode agent** (Réglages, par opérateur) : dépôts et retraits clients classés automatiquement, avec effet sur les espèces.
- **Excel** : onglets Synthèse (totaux par catégorie, résultat, comptes, espèces), Journal de caisse (avec Catégorie et Motif), À classer, Espèces, À vérifier.

Limite connue : les formats de SMS des comptes agents n'ont pas encore été vus. Envoyez des SMS de dépôt et de retrait clients anonymisés (« Plus… → Exporter les SMS anonymisés ») pour fiabiliser le mode agent.

## Nouveautés de la version 0.2

- Un seul compte par opérateur : l'identifiant de SIM d'Android, qui change avec le téléphone ou la carte, n'éclate plus un compte en plusieurs « SIM ».
- Lecture refaite à partir de vrais SMS 2026 : transferts OM reçus ou envoyés selon le numéro du titulaire (détecté automatiquement, modifiable dans Réglages), type d'opération OM lu dans le préfixe de l'ID (PP, CI, CO, MP, RC), ID MoMo séparés du texte publicitaire, montants avec centimes.
- Les consultations de solde servent de points de contrôle du solde réel au lieu d'être comptées comme des entrées.
- Les SMS de crédit téléphonique (« airtime ») et les doublons de paires de SMS MoMo ne faussent plus les totaux.
- Choix de la période : Aujourd'hui, 7 jours, Ce mois, Tout, ou « Période précise… » (date de début puis date de fin). Le solde d'ouverture est repris du dernier SMS avant la période ; l'écran et l'export Excel suivent la période choisie.
- Export Excel (.xlsx) par défaut : onglets Synthèse, Journal et À vérifier. CSV et SMS anonymisés dans « Plus… ».
- Testé sur une sauvegarde réelle de 995 SMS : MoMo écart 0 FCFA ; Orange Money écart −2 006 FCFA, expliqué par deux SMS absents signalés dans le journal.

Pour mettre à jour : déposez le contenu du zip dans le dépôt (les fichiers existants sont remplacés). L'APK 0.2 s'installe par-dessus la 0.1, sans désinstaller.

## Ce que fait le prototype

- Au premier lancement, relit la messagerie et retient uniquement les SMS Orange Money et MTN MoMo.
- Ensuite, capte chaque nouveau SMS OM ou MoMo en temps réel, même application fermée, et affiche une notification (montant, contrepartie, nouveau solde).
- Garde une copie locale de ces SMS : l'historique reste disponible même si les SMS sont supprimés de la messagerie.
- Affiche par compte (et par SIM en double SIM) : solde réel, solde de départ, entrées, sorties, frais, solde théorique, écart.
- Contrôle la chaîne des soldes et signale les ruptures (SMS manquant ou montant mal lu).
- Liste les SMS mobile money non compris dans l'onglet « À vérifier ».
- Exporte le journal et la synthèse en CSV (ouvrable dans Excel ou Google Sheets).
- Rien n'est envoyé sur internet : l'application n'a même pas la permission d'accès à internet.

## Obtenir l'APK sans rien installer (GitHub)

1. Créez un compte gratuit sur https://github.com puis un nouveau dépôt **privé** nommé `carnet-mobile-money`.
2. Sur un ordinateur, décompressez le zip, puis dans le dépôt : **Add file → Upload files**, glissez **tout le contenu** du dossier `CarnetMobileMoney` (y compris le dossier `.github`) et validez (**Commit changes**).
   - Si le dossier `.github` n'est pas envoyé (dossier caché) : **Add file → Create new file**, tapez le nom `.github/workflows/build.yml`, collez le contenu du fichier `build.yml` fourni, puis validez.
3. Ouvrez l'onglet **Actions** : la compilation « Compiler l'APK » démarre seule et dure environ 5 minutes.
4. Quand elle est verte, ouvrez **Releases** (colonne de droite de la page du dépôt) depuis le téléphone, connecté à GitHub, et téléchargez `carnet-mobile-money.apk`.

Chaque modification envoyée dans le dépôt produit automatiquement un nouvel APK.

## Ou avec Android Studio (sur ordinateur)

Ouvrez le dossier `CarnetMobileMoney` dans Android Studio, attendez la synchronisation, puis **Build → Build APK(s)**. L'APK se trouve dans `app/build/outputs/apk/debug/`.

## Installer et autoriser sur le téléphone

1. Ouvrez l'APK téléchargé. Android demande d'autoriser l'installation depuis le navigateur ou le gestionnaire de fichiers : acceptez.
2. Si Play Protect affiche un avertissement (application inconnue), touchez **Plus de détails → Installer quand même**. C'est normal pour une application hors Play Store qui lit des SMS.
3. Ouvrez **Carnet Mobile Money** et touchez **Autoriser la lecture des SMS**.
4. Si l'autorisation SMS est grisée ou refusée (Android 13 et plus, applications installées hors Play Store) :
   **Paramètres → Applications → Carnet Mobile Money → menu ⋮ en haut à droite → Autoriser les paramètres restreints**, puis **Autorisations → SMS → Autoriser**. Le bouton « Ouvrir les paramètres de l'application » y mène directement.
5. Sur Tecno, Infinix, Itel, Xiaomi : dans les paramètres de l'application, réglez la batterie sur **Aucune restriction** pour que la capture en temps réel fonctionne application fermée.

## Que tester

- [ ] Les comptes OM et MoMo apparaissent avec un solde réel égal à celui de votre dernier SMS.
- [ ] Le solde réel correspond au solde affiché par #150# ou *126#.
- [ ] Un nouveau transfert reçu déclenche une notification en quelques secondes, application fermée.
- [ ] Les ruptures de chaîne correspondent à de vrais SMS manquants, ou à des SMS mal lus.
- [ ] L'export CSV s'ouvre correctement dans Excel ou Google Sheets.
- [ ] Onglet « À vérifier » : copiez ces SMS (appui long sur le texte), masquez noms et numéros, et envoyez-les pour améliorer les règles de lecture.

## Si l'expéditeur n'est pas reconnu

Touchez **Réglages** et ajoutez le nom d'expéditeur exact affiché dans votre messagerie (par exemple `OrangeMoney` ou `MobileMoney`).

## Limites du prototype

- Les règles de lecture sont génériques : elles doivent être ajustées sur un corpus de vrais SMS.
- Pas encore de compte utilisateur, de sauvegarde en ligne, d'abonnement ni de caisse espèces.
- La clé de signature fournie sert uniquement aux tests : elle doit être remplacée par une clé privée avant toute diffusion.

## Organisation du code

| Fichier | Rôle |
| --- | --- |
| `SmsParser.java` | Reconnaît l'opérateur et extrait montant, frais, solde, ID, contrepartie (Java pur, testable sur ordinateur) |
| `Analyzer.java` | Dédoublonnage, chaîne des soldes, solde réel et théorique par compte (Java pur) |
| `Txn.java` | Modèle d'une transaction |
| `Store.java` | Copie locale des SMS mobile money (SQLite) et réglages |
| `SmsReceiver.java` | Capte les nouveaux SMS en temps réel et notifie |
| `MainActivity.java` | Écran : autorisation, comptes, journal, à vérifier, réglages, export CSV |
