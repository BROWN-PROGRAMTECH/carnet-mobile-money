# Carnet Mobile Money — prototype Android

Application Android qui lit directement les SMS Orange Money et MTN MoMo du téléphone, sans export.

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
