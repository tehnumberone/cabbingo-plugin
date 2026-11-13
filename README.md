# Cabbingo

Sends your bingo drops, kills, pets and skilling progress to your team on [Cabbingo](https://www.cabbingo.com), and shows a popup with a sound when one of your team's tiles is completed.

This plugin was made to make creating and playing bingo's alot easier. You set up your bingo board on the site, you use the team url to link your client to your team, and it uses your RSN to compare against a whitelist of that bingo board. This way you got an immersive experience, bingo board ingame, you get an item for a tile and you complete it? skadoosh: pop up with tile completed to everyone in your team!

## Setup

### Set up a bingo board

For the organiser, on [cabbingo.com](https://www.cabbingo.com):

1. Log in, then on **All boards** press **Create a bingo** and pick a game mode.
2. In **Board settings**, on the **General** tab, set the title, start and end. Under **Drops**, set **Drops come from** to **Cabbingo plugin**.
3. Add the tiles on the **Tiles** tab, and the teams with their players and captains on the **Teams** tab. Save the bingo.
4. Back on **General**, under **Drops**, paste a Discord webhook for each team and press **Save** next to it. That team's drops and screenshots go to this channel.

### Get your Team URL

The Team URL comes from your bingo's **Captains' board**. Each team has its own URL.

1. Open the bingo and press **Captains' board**, then **Drop setup**. If you manage more than one team, pick the team first.
2. Copy the **Team URL**. Until the team's Discord webhook is saved in Board settings, Drop setup shows "Not set up yet".
3. Share the URL with your team only.
4. Each player pastes it into the plugin's **Team URL** setting.

Nothing is sent before a Team URL is set, and nothing is sent anywhere else.

## What it does

- Posts drops, kills, pets and skilling counts that count towards one of your team's tiles. A screenshot goes along when it matters; it goes to your team's Discord channel and never shows your private chat.
- Shows a popup and plays a sound when one of your team's tiles, or its flipped side, is completed.
- Adds a board button that opens your team's bingo board in the game.
- On logout during a bingo, asks TempleOSRS to update your XP, unless RuneLite's XP Updater already does.

## Settings

| Setting | What it does |
|---|---|
| Team URL | Your team's URL from Drop setup on the Captains' board. |
| Sound / Flipped tile sound | The sound for a completed tile or flipped side. |
| Volume | Volume of the sounds. Game sounds follow your game's sound effect volume unless it is muted. |
| Test sound / Test flipped tile sound | Plays the sound. |
| Board button | Shows the button that opens your bingo board. |
| Open board on right-click only | Left clicks go through the button to the game. |
| Hide tile details on outside click | A click outside a tile hides the picked tile's details. |
| Always close board on ESC | ESC closes the board even while a game window is open. |
| Board tile size | The size of a tile on the board. Dragging the board's edges changes it too. |
| Board background | The colour of the board and its button. |
| Download bingo board images | Off by default. Shows pictures the bingo owner uploaded on tiles; they can contain any imagery. Off: those tiles show their name. Items, bosses and skills always use the game's own icons. |

## Privacy

This plugin submits your IP address, your RuneScape name and your loot to a 3rd-party server not controlled or verified by the RuneLite developers.

## License

BSD 2-Clause, see [LICENSE](LICENSE).
