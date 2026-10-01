# Cobble Auto Miner 2 (Fabric 1.21.1) - version a plat

Tous les fichiers sont a la racine (pas de dossiers). Le chemin d'origine est dans le nom
du fichier, avec "__" a la place de "/". Le workflow GitHub les remet dans les bons dossiers
avant de compiler.

## Etapes sur GitHub
1. Cree un depot, puis "Add file" > "Upload files" et envoie tous les fichiers SAUF `build.yml`.
2. "Add file" > "Create new file", tape comme nom `.github/workflows/build.yml`
   (les "/" creent les dossiers tout seuls), colle le contenu de `build.yml`, puis "Commit".
3. Onglet Actions > "build" (ou Run workflow) > telecharge l'artefact `cobble-auto-miner`.
   Le .jar est dedans. Supprime l'ancien jar du dossier mods (meme id de mod).

## Touches (pave numerique, modifiables dans Options > Commandes > Cobble Auto Miner)
- `.`  (KP_DECIMAL) : activer / desactiver
- `0`  (KP_0)       : ouvrir l'interface
- `*`  (KP_MULTIPLY): ESP on/off
- `/`  (KP_DIVIDE)  : recharger les chunks

Config : config/cobbleautominer-v2.json
