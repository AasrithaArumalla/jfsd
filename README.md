# ClubTrack — College Club Asset Handover System

A lightweight full-stack web application for tracking shared club assets and their custody transfers.

## Included workflow

1. Register assets with a unique asset ID, category, condition, and storage location.
2. Search the club-wide asset register and see its current holder/status.
3. Create a handover request between an outgoing and incoming member.
4. Review and approve pending transfers.
5. On approval, the system updates the asset holder and preserves the completed handover record.

## Run it

The bundled Node runtime does not include npm, so no package installation is needed.

```powershell
node server.js
```

Then open [http://localhost:3000](http://localhost:3000).

Data is stored in `data.json`, which is created automatically with demo records on the first run.

## Run it in Eclipse (Java)

1. In Eclipse, choose **File → Import → Existing Projects into Workspace** and select this folder.
2. Set the project JRE to **Java 17** (or later).
3. Open `src/clubtrack/ClubTrackServer.java` and run `ClubTrackServer` as a Java Application.
4. Open `http://localhost:3000` in a browser.

This Java version uses the same interface in `public/` and the same asset/handover APIs. Each open screen checks for updates every 8 seconds, so newly requested and approved transfers appear live without manually refreshing.

## Project structure

- `server.js` — REST API and static-file server
- `public/index.html` — application layout
- `public/app.js` — UI behavior and API integration
- `public/styles.css` — responsive visual design
- `data.json` — locally persisted assets and handovers
