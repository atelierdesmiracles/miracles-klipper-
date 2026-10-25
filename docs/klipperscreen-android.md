# KlipperScreen sur Android

Miracle Klipper installe une révision KlipperScreen épinglée et ses dépendances Debian dans le rootfs PRoot. L’installation n’exécute pas le script amont qui configure systemd et un écran Linux directement : Android démarre KlipperScreen comme un processus géré par l’application.

Pour afficher l’interface sur l’écran du téléphone :

1. Installer XServer-XSDL depuis sa page officielle liée dans les paramètres.
2. Connecter le téléphone au Wi-Fi et choisir « Open KlipperScreen on this phone » dans les paramètres.
3. Utiliser la notification ou le bouton « Stop KlipperScreen » pour arrêter l’affichage.

Au démarrage, l’application lance XServer-XSDL, attend que son serveur X réponde sur le port 6000, puis démarre `screen.py` avec l’adresse IPv4 Wi-Fi du téléphone. Le processus s’exécute dans un service Android de premier plan et écrit ses journaux dans `files/miracle-klipper/logs/klipperscreen-process.log`. Le journal d’installation se trouve à `files/miracle-klipper/logs/klipperscreen-install.log`.

Références amont : [guide Android de KlipperScreen](https://github.com/KlipperScreen/KlipperScreen/blob/master/docs/Android.md), [installation](https://github.com/KlipperScreen/KlipperScreen/blob/master/docs/Installation.md), [XServer-XSDL sur SourceForge](https://sourceforge.net/projects/libsdl-android/files/apk/XServer-XSDL/).

L’option Android utilise XServer-XSDL, que la documentation KlipperScreen signale comme moins fluide et potentiellement sujet à des fuites mémoire; le guide recommande VNC comme autre méthode. Ce mode doit donc encore être validé sur le téléphone cible.
