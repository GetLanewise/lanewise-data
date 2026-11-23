# Ontbrekende trajectcontroles intekenen in OpenStreetMap

Stand 2026-11-23. Het ophaalscript (`FetchSections.java`) vergelijkt elke nacht met `officiele-trajecten.txt`.
Deze 11 provinciale trajecten staan in de officiële lijst (Ministerie van JenV, 1e tertiaal 2025), maar niet in OSM.

## Bronnen die wel en niet mogen

- **Wel:** PDOK-luchtfoto (in iD: "PDOK Luchtfoto Actueel HR"), Mapillary en Panoramax (in iD ingebouwd), eigen waarneming.
- **Niet:** Google Maps / Street View, Flitsmeister, of andere apps en kaarten met een licentie. OSM verwijdert edits uit zulke bronnen.
- Beschrijvingen uit nieuwsberichten ("tussen X en Y") mag je gebruiken om te zoeken. Het punt zelf zet je op wat je op de foto ziet.

## Hoe een traject in OSM staat (zo leest ons script het)

Een traject is een relatie met deze tags, één relatie per rijrichting:

- `type=enforcement`
- `enforcement=average_speed`
- `maxspeed=80`
- `name=Trajectcontrole N351 Oldeberkoop - Nijeholtpade` (naam met wegnummer)
- `operator=Centraal Justitieel Incassobureau`

Leden van de relatie:

- rol `from`: een punt op de weg bij de begincamera (in de rijrichting);
- rol `to`: een punt op de weg bij de eindcamera;
- rol `device`: de camerapalen zelf (`highway=speed_camera` of `man_made=surveillance`), als ze er al staan;
- rol `section`: alle wegdelen ertussen. Splits de weg zo nodig bij het begin- en eindpunt.

Kijk een bestaand traject na als voorbeeld, bijvoorbeeld relatie 1593239 (A12).

## Per traject

Legenda:

- ✅ = camerapunt gezien op de PDOK-luchtfoto (paal met arm over de weg plus kastje);
- 🔎 = aanwijzing in OSM, nog niet bevestigd;
- ❓ = nog zoeken.

| Weg | Traject (officieel) | Wat we al weten | Te doorzoeken stuk |
|---|---|---|---|
| N787 | Eerbeek - Brummen | ✅ beide einden. West: node 9571872873/9571872874 (52.09070, 6.09553). Oost: node 9571872871/9571872872 (52.08916, 6.13582). Beide punten bedienen beide richtingen (paal met arm). Tussenstuk ±2,8 km, geen andere palen gezien. | — klaar om in te tekenen |
| N351 | Oldeberkoop - Nijeholtpade | ✅ oostkant: node 11109589327 en 11109589328 (52.93114, 6.12834), één paal per richting. ❓ westkant richting Nijeholtpade (Stellingenweg). | Stellingenweg 52.881, 6.009 → 52.933, 6.133 |
| N230 | Lageweide - Overvecht (Zuilense Ring, Utrecht) | 🔎 vijf camera's bij 52.1174, 5.0327 (aansluiting A2) en twee bij 52.1377, 5.0739. OM: "tussen het Gandhiplein en de A2". | Zuilense Ring 52.119, 5.035 → Gandhiplein 52.129, 5.098 |
| N414 | Eembrugge - Bunschoten | ❓ Bisschopsweg. Op de luchtfoto (middagfoto, korte schaduwen) geen palen te herkennen. | 52.218, 5.305 → 52.227, 5.368 |
| N706 | Almere Hout - N302 (Vogelweg) | ❓ | Vogelweg 52.350, 5.336 → 52.445, 5.552 (±19 km; zoek bij de einden) |
| N275 | Koningslust - Nederweert | 🔎 flitspalen in OSM bij 51.2954, 5.8138 / 51.3061, 5.8455 / 51.3105, 5.8584. Op de luchtfoto niets gezien bij de eerste. | Venloseweg 51.281, 5.770 → Kanaaldijk 51.326, 5.904 |
| N564 | Kazerneweg - Havenweg (Weert - grens België) | 🔎 politiecamera (ALPR) bij 51.2175, 5.5854 (grenskant). | Kempenweg 51.217, 5.583 → 51.246, 5.668 |
| N277 | Vredepeel - Ysselsteyn | ❓ | 51.538, 5.845 → 51.493, 5.892 |
| N270 | Ysselsteyn - Venray | 🔎 flitspaal bij 51.5111, 5.9473; op de luchtfoto onduidelijk. | 51.492, 5.861 → 51.516, 5.985 |
| N260 | Rugdijk - Stokhasseltlaan (Tilburg-Noord) | ❓ Burgemeester Letschertweg, oostelijk deel. | 51.556, 4.951 → 51.608, 5.071 |
| N639 | Baarle-Nassau - Chaam | ❓ | Bredaseweg 51.504, 4.871 → 51.458, 4.914 |

## Snelste werkwijze in iD (openstreetmap.org → Bewerken)

1. Ga naar het te doorzoeken stuk.
2. Zet onder Kaartgegevens (de toets F) "Mapillary-verkeersborden" en "Mapillary-kaartobjecten" aan. Op de foto's zie je het bord "trajectcontrole" en de palen met camera's.
3. Achtergrond (de toets B): kies "PDOK Luchtfoto Actueel HR" om de paal precies te plaatsen. Een kastje en de schaduw van de arm over de weg zijn de herkenningstekens.
4. Teken eerst de camerapunten. Maak daarna de relatie zoals hierboven.
5. Bronvermelding bij het opslaan: `source=PDOK luchtfoto; Mapillary`.

De nacht na het intekenen staat het traject in `osm-nl-trajecten.json`. De vergelijking in het verslag laat dan één ontbrekend traject minder zien.
