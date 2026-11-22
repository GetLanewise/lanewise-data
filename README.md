# flitsapp-data

Dagelijks bijgewerkte lijst van vaste flitsers in Nederland, voor de Flitsapp.

- `osm-nl.json` – vaste flitsers. Zonder maxspeed-label is de limiet aangevuld met die van de weg (`"maxSpeedSrc":"road"`; bij twijfel tussen wegen de laagste).
- `osm-nl-trajecten.json` – trajectcontroles (begin, einde, limiet, wegdelen, camera-punten).
- `tools/FetchCameras.java` en `tools/FetchSections.java` – halen de data op bij OpenStreetMap (Overpass) en zetten die om.
- `.github/workflows/update-data.yml` – draait het script elke nacht en commit alleen bij wijzigingen.

## Bron en licentie van de data

De data in `osm-nl.json` en `osm-nl-trajecten.json` komt uit OpenStreetMap:
**© OpenStreetMap-bijdragers**, beschikbaar onder de
[Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Zie https://www.openstreetmap.org/copyright.

Dit bestand bevat bewust alleen OpenStreetMap-data. Andere bronnen of eigen gebruikersdata
worden hier nooit mee samengevoegd.

## Handmatig draaien

Java 17 of nieuwer:

```
java tools/FetchCameras.java osm-nl.json
```

Het script overschrijft het bestaande bestand niet als het ophalen mislukt, als er minder
dan 300 flitsers binnenkomen of als het aantal meer dan 30% daalt.
