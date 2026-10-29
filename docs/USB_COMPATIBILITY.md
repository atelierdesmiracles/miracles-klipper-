# Miracle Klipper — compatibilité USB imprimantes 3D

## Objectif et périmètre

Miracle Klipper vise la compatibilité avec le plus grand nombre de cartes contrôleurs d'imprimantes 3D pouvant communiquer avec Klipper et exposer une interface USB accessible depuis Android. La compatibilité ne peut pas être garantie pour toutes les imprimantes : certaines utilisent un firmware propriétaire, une liaison non prise en charge ou un périphérique qui ne fonctionne pas en USB Host/OTG.

## Architecture de détection attendue

1. Énumérer tous les périphériques via Android `UsbManager.deviceList`. La découverte ne doit pas être limitée à `device_filter.xml` ni aux VID/PID connus.
2. Afficher les informations USB disponibles : nom, VID, PID, classes, interfaces et état d'autorisation.
3. Détecter les pilotes série standards avec la bibliothèque USB série intégrée : CDC ACM, FTDI, CP210x, CH34x et Prolific.
4. Pour un VID/PID inconnu, proposer une sélection manuelle de pilote, mais ne pas affirmer que le périphérique est compatible avant qu'une ouverture du port et un échange de données réussissent.
5. Garder le port sélectionné stable : ne pas remplacer automatiquement le contrôleur choisi par un autre périphérique USB découvert ensuite.
6. Gérer l'autorisation Android, la déconnexion/reconnexion, les erreurs d'ouverture, les délais et la fermeture propre du port.
7. Exposer des diagnostics compréhensibles : aucun périphérique vu par Android, périphérique vu mais pilote inconnu, autorisation refusée, ouverture série échouée, liaison ouverte, données reçues.
8. Le pont série vers Klipper doit être créé et monté dans l'environnement Linux avant le démarrage de Klipper si `printer.cfg` utilise `/dev/ttyOcto4a`.

## Compatibilité firmware

- Une carte exécutant Klipper et exposant un port série USB compatible peut être candidate.
- Les cartes qui exécutent Marlin ou un firmware propriétaire ne deviennent pas compatibles avec Klipper par simple détection USB. Elles nécessitent une migration/installation Klipper compatible avec la carte.
- Les cartes exigeant un protocole propriétaire, un pilote USB inaccessible ou un mode USB non pris en charge ne peuvent pas être garanties.

## Tests de validation

- Tests unitaires des tables VID/PID et de la classification des pilotes.
- Test Android de détection de périphériques inconnus (ne pas filtrer sur les VID/PID).
- Test de permission USB sur Android 11, 12+ et versions récentes prises en charge.
- Test de connexion/déconnexion, sélection manuelle et reconnexion.
- Test matériel réel avec plusieurs contrôleurs CDC ACM, CH34x, FTDI, CP210x et Prolific.
- Test complet de l'échange série entre Klipper et la carte, et pas seulement de la visibilité USB.

## Limite importante

Les tests unitaires et CI ne prouvent pas une compatibilité physique. Chaque famille de contrôleurs doit être validée sur matériel réel, avec téléphone, câble de données et adaptateur OTG identifiés.
