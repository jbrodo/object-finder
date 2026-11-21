---
name: ObjectFinder
description: Native Android inventory that records where physical things are and leads you back to them.
colors:
  survey-blue: "#1F5F8B"
  on-survey-blue: "#FFFFFF"
  survey-blue-container: "#CDE5FF"
  on-survey-blue-container: "#001D32"
  pencil-slate: "#52606D"
  brass-compass: "#8A5A00"
  error-red: "#BA1A1A"
  survey-blue-night: "#94CCFF"
  on-survey-blue-night: "#003352"
  survey-blue-container-night: "#004B74"
  on-survey-blue-container-night: "#CDE5FF"
  pencil-slate-night: "#B9C8D8"
  brass-compass-night: "#FFB951"
  photo-scrim: "#000000"
  photo-ink: "#FFFFFF"
typography:
  headline:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "24px"
    fontWeight: 400
    lineHeight: "32px"
    letterSpacing: "0em"
  title-large:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "22px"
    fontWeight: 400
    lineHeight: "28px"
    letterSpacing: "0em"
  title:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 500
    lineHeight: "24px"
    letterSpacing: "0.009em"
  title-small:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: "20px"
    letterSpacing: "0.007em"
  body-large:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
    letterSpacing: "0.031em"
  body:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
    letterSpacing: "0.018em"
  body-small:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "16px"
    letterSpacing: "0.033em"
  label:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: "20px"
    letterSpacing: "0.007em"
rounded:
  badge: "4px"
  photo: "8px"
  thumb: "12px"
  panel: "16px"
  pill: "9999px"
spacing:
  hair: "2px"
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  fab-clearance: "96px"
components:
  button-primary:
    backgroundColor: "{colors.survey-blue}"
    textColor: "{colors.on-survey-blue}"
    rounded: "{rounded.pill}"
    height: "40px"
    padding: "0 24px"
  button-outlined:
    textColor: "{colors.survey-blue}"
    rounded: "{rounded.pill}"
    height: "40px"
    padding: "0 24px"
  button-text:
    textColor: "{colors.survey-blue}"
    rounded: "{rounded.pill}"
    height: "40px"
    padding: "0 12px"
  place-selector:
    textColor: "{colors.survey-blue}"
    rounded: "{rounded.badge}"
    height: "40px"
    width: "100%"
  object-thumb:
    rounded: "{rounded.thumb}"
    size: "64px"
  panel-where:
    backgroundColor: "{colors.survey-blue-container}"
    textColor: "{colors.on-survey-blue-container}"
    rounded: "{rounded.panel}"
    padding: "16px"
  panel-map-group:
    rounded: "{rounded.panel}"
    padding: "4px 4px 16px 16px"
  photo-badge:
    backgroundColor: "{colors.photo-scrim}"
    textColor: "{colors.photo-ink}"
    rounded: "{rounded.badge}"
---

# Design System: ObjectFinder

## Overview

**Creative North Star: "The Field Notebook"**

ObjectFinder is a surveyor's notebook for things you own: each entry records what an object is, where it was last put, how precise that fix is, and who has it now. The answer to "where is it?" always comes first. It is written as a plain sentence or a place path (Casa › Garage › Scaffale), followed by the distance and how old the reading is, with the next action (*Portami lì*, *L'ho spostato qui*) directly below. The tone is calm and reassuring. Someone opening the app has usually lost something, and the interface should lower that stress, not add to it.

The visual layer is deliberately stock Material 3. The app follows the phone's wallpaper colors (dynamic color on Android 12+), uses the system type scale, and draws its identity from structure, not ornament: soft tonal **answer panels** that hold the important facts, consistent photo thumbnails, honest uncertainty language ("±40 m"), and a status vocabulary carried by color *roles*: primary means "here it is", tertiary means "it's on loan", error means "needs you". Density is medium. Lists are scannable one-handed, and the object page is a vertical stack of panels.

The Survey Blue palette is the **fallback** identity for Android 11 and below and the launcher icon's ground. It is not the color users see on modern devices.

**Key Characteristics:**
- Wallpaper-first color: rules are written in Material color roles, never hex.
- The "where" answer leads every surface; facts are joined with ` · `.
- Tonal answer panels (16px radius) hold location, loan, and conflict states.
- Only the user's own photos appear; no illustration, no stock art.
- System type scale, no branded font.
- Italian UI copy, sentence case, verbs on buttons.

## Colors

A role-driven palette: on Android 12+ every role comes from the user's wallpaper. The values below are the fallback scheme and the identity of the launcher icon.

### Primary
- **Survey Blue** (light fallback; Survey Blue Night in dark): the "here it is" color. Filled buttons, the distance-from-you line on the map panel, selected-item check overlay (at 55% alpha over the thumbnail), launcher icon background.
- **Survey Blue Container**: background of the *Dove si trova* answer panel on the object page, the single most important surface in the app.

### Secondary
- **Pencil Slate**: quiet supporting accent. Its container role tints a selected object card in multi-select mode.

### Tertiary
- **Brass Compass**: the loan color. Its container role is the background of the *In prestito* panel when the loan is not overdue.

### Neutral
- **Surface / On Surface Variant** (Material defaults): `onSurfaceVariant` is the workhorse secondary text color (the most-used color role in the codebase), for metadata lines, sync icons, empty-thumbnail initials. `surfaceVariant` fills empty photo thumbnails.
- **Photo Scrim / Photo Ink**: black at 45–55% alpha with white content, used *only* over camera previews and photos (viewer controls, photo-count badge, shutter ring). This is the one sanctioned use of literal colors.

### Error
- **Error Red**: conflicts, overdue loans, validation. `errorContainer` backs the sync-conflict banner and an overdue loan panel; the conflict warning icon on a list card uses `error` directly.

### Named Rules
**The Roles-Not-Hex Rule.** UI code references `MaterialTheme.colorScheme` roles only. A hex literal in a composable is a bug, except Photo Scrim/Ink over imagery and the black-on-white QR label.

**The Three Meanings Rule.** Primary container = where it is. Tertiary container = lent out. Error container = needs your decision. Don't spend these containers on anything else, or the status language stops working.

**The Complete Fallback Rule.** The fallback scheme in `Theme.kt` defines every Material role, including containers, surfaces and outlines, with tones derived from Survey Blue, Pencil Slate and Brass Compass (container = tone 90 light / 30 dark). New roles must be derived the same way; never let a role fall through to Material's baseline lavender.

## Typography

**Display Font:** Roboto (system default, via Material 3's default `Typography`)
**Body Font:** Roboto
**Label/Mono Font:** none distinct

**Character:** Neutral and legible, like field notes written in plain block letters. Hierarchy comes from size and weight steps of the Material scale, never from a second typeface.

### Hierarchy
- **Headline** (400, 24px / 32px): rare; screen-level empty states and auth headings.
- **Title Large** (400, 22px / 28px): the *answer* inside a panel: the place path, the borrower's name ("a Marco"), the empty-thumbnail initial.
- **Title** (500, 16px / 24px): object names in list cards, single line with ellipsis.
- **Title Small** (500, 14px / 20px): banner and section titles (conflict banner heading).
- **Body Large** (400, 16px / 24px): facts in panels: distance, age of reading, loan dates.
- **Body** (400, 14px / 20px): the default running text and the "where" line under a list card's name. The most used style.
- **Body Small** (400, 12px / 16px): precision lines, supporting detail, diff rows in the conflict banner.
- **Label** (500, 14px / 20px): panel eyebrows (*Dove si trova*, *In prestito*, *In questo punto*), marked as semantic headings.

### Named Rules
**The Eyebrow-Then-Answer Rule.** Every answer panel opens with a Label eyebrow (semantic heading) followed by a Title Large answer. The eyebrow names the question; the title answers it.

**The Dot-Join Rule.** Related facts on one line are joined with ` · ` (place path · detail, distance · age), never with commas or pipes.

## Layout

Single-column, phone-first. List screens use a `LazyColumn` with 16px side padding, 12px top padding and 8px between cards. Bottom padding is 96px so the last card clears the Extended FAB. Filter chips sit in a horizontal row with 8px gaps above the list.

The object page is a vertical stack of sections spaced 12px apart. Inside panels and cards the rhythm is 16px padding with 4–8px between lines (6px in the answer panels). Inline icons inside buttons and metadata are 18px, separated from their label by `ButtonDefaults.IconSpacing` (8px), never by spaces inside the string.

**Adaptive widths.** Forms and reading surfaces (object page, settings, projects, members, sign-in) fill the window up to 640px and then stay centered (`Modifier.readableWidth()`); the search field stops at the same width. The object list is an adaptive grid with cards at least 360px wide: one column on phones and folded devices, two or three on tablets. The map panel stops at 560px.

**The Window-Class Rule.** Layout decisions read the window size class, never orientation or "is tablet". Every new screen is checked at phone (411px), foldable (673px) and tablet (1280px) widths through the screenshot previews.

The map screen is a full-bleed map with filter chips overlaid at the top and the selected-group panel docked at the bottom; its item list is capped at 240px and scrolls.

The spacing scale in practice: 2 / 4 / 8 / 12 / 16 / 24px (1px = 1dp). Anything else is a one-off.

## Elevation & Depth

Follow Material 3 defaults: cards, chips, dialogs, bottom sheets, and the FAB use their standard tonal and shadow elevations. The system adds no custom shadow vocabulary. The one explicit override is the map's selected-group panel (tonal elevation 3, shadow 6), which needs to read clearly over busy map tiles.

## Shapes

Gently rounded, Material-led. Buttons, chips and FABs keep their default shapes (fully rounded pills for buttons). Custom radii form a small, consistent ladder:

- **Badge** (4px): photo-count badge over images and the place-selector button, which deliberately squares off to read as a field, not an action.
- **Photo** (8px): photo grid tiles in the photo section.
- **Thumb** (12px): the 64px object thumbnail in list cards and recognition results.
- **Panel** (16px): answer panels (*Dove si trova*, *In prestito*) and the floating map panel.

Circles are reserved for the camera shutter.

## Components

The components should feel calm and reassuring: soft tonal containers, clear verbs, nothing that shouts.

### Buttons
- **Shape:** Material default pill (fully rounded, 40px tall).
- **Primary (Button):** the single most important action on a surface, e.g. *Portami lì* (full width, with a Directions icon) or *Tieni la mia* in a conflict.
- **Outlined:** the frequent secondary action, e.g. *L'ho spostato qui* or *Usa quella del server*. Often full width inside panels.
- **Filled in panel color:** confirming a state change inside a tinted panel (*Restituito* is `tertiary` on the loan panel, `error` when overdue).
- **Text:** the most common button. Dialog actions, low-emphasis corrections (*Correggi il punto sulla mappa*), "Modifica".
- **Loading:** an in-progress action swaps its icon for a 16px circular indicator (2px stroke) and changes its label to describe what's happening ("Rilevo la posizione…", "Cerco un segnale migliore… ±12 m").

### Chips
- **Style:** Material `FilterChip` in a horizontal row, 8px apart, above lists and over the map.
- **State:** selected/unselected toggles filter the visible set (sort/filter options, *Vicini*, by place).

### Cards / Containers
- **Object card:** default Material `Card`, a 64px thumbnail (12px radius) flush to a 12px start inset, then a 16px-padded text column: name (Title, one line), where-line (Body), metadata row with 18px icons. With no photo, the thumbnail shows the name's initial in Title Large on `surfaceVariant`. Selected state tints the card `secondaryContainer` and overlays a primary-tinted check on the thumbnail.
- **Background / Shadow:** Material card defaults; see Elevation & Depth.

### Inputs / Fields
- **Style:** Material `OutlinedTextField` throughout, with floating label, an example placeholder ("es. garage, scaffale in alto a sinistra"), and supporting text that teaches why the field matters. A character counter appears only near the limit.
- **Place selector:** an outlined button with a 4px radius, a Place icon and the current path, which opens the tree picker. It looks like a field because it acts as one.

### Navigation
- **Style:** adaptive navigation (`NavigationSuiteScaffold`) with three labelled tabs (*Oggetti*, *Mappa*, *Impostazioni*): a bottom bar on compact windows (<600dp), a navigation rail from 600dp up (tablets, open foldables, free-form windows). The choice follows the window size class, never orientation or device type. A shared `TopAppBar` carries the current project's name; on the object list it holds icon actions (scan label, recognize object, sync with a pending-count badge) and an overflow menu (*Esporta inventario*). The object page, projects and members open as stacked screens with a back arrow. Creation is an Extended FAB on the list.

### Answer Panel (signature)
A `Surface` with a 16px radius and 16px padding, tinted by meaning: `primaryContainer` for *Dove si trova*, `tertiaryContainer` for *In prestito*, `errorContainer` when that loan is overdue. Content follows the Eyebrow-Then-Answer rule, then fact lines in Body Large / Body Small, then actions. Actions take the panel's own color, not the generic ones: on the loan panel the main button is `tertiary` (`error` when overdue) and secondary text buttons use the panel's content color. This is the component that makes ObjectFinder recognizable: the answer is handed to you in a calm, colored slip of paper.

### Conflict Banner (signature)
An `errorContainer` card at the top of the object page when the server version diverged: warning icon + Title Small heading, an explanation in Body, a per-field diff (Label bold field name, "Tua:" / "Server:" lines in Body Small), and a Button/Outlined pair to choose a version.

### Messages
Short feedback goes through one Snackbar (`LocalAppMessages`), never a Toast. A message that has a remedy carries it as the action: GPS unavailable → *Impostazioni*, bulk archive → *Annulla*. Inside a dialog, a bottom sheet or the camera, where a Snackbar would sit under the scrim, the message appears inline in that surface (error color for failures, announced as a polite live region).

### Camera & Photo Overlays
Full-black canvases with white controls on 45–55% black scrims, a top gradient for legibility, and a 4px white-ringed circular shutter. The only place the UI leaves the color-role system.

## Do's and Don'ts

### Do:
- **Do** reference `MaterialTheme.colorScheme` roles and `MaterialTheme.typography` styles in every composable.
- **Do** lead each surface with the "where" answer: place path or description first, then distance · age.
- **Do** use the Answer Panel (16px radius, 16px padding, Label eyebrow + Title Large answer) for any new status-bearing summary.
- **Do** keep tinted containers for their meanings: primary = location, tertiary = loan, error = needs a decision.
- **Do** state uncertainty plainly ("±40 m", "aggiornata 3 giorni fa") and offer the correcting action right below it.
- **Do** add every new screen or panel to `Previews.kt` and record it (`./gradlew :app:recordRoborazziDebug`), in light and dark, at phone and tablet widths.
- **Do** give the "where" line in list cards two lines before ellipsizing; it is the answer the user is looking for.
- **Do** keep inline icons at 18px and list thumbnails at 64px with a 12px radius so rows align with or without photos.

### Don't:
- **Don't** hard-code colors in UI code; the only exceptions are photo/camera scrims and the black-on-white QR label.
- **Don't** introduce custom fonts or a branded typeface; the system type scale is the voice.
- **Don't** add decorative imagery: no illustrations, stock photos, or mascots. Only the user's own photos appear.
- **Don't** use primary, tertiary or error containers for decoration or emphasis unrelated to their meaning.
- **Don't** use Toasts or pad strings with spaces for layout.
- **Don't** add a custom shadow vocabulary; Material elevation defaults cover everything except the map panel.
