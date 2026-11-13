# Cabbingo

Sends your bingo drops, kills, pets and skilling progress to your team on [Cabbingo](https://www.cabbingo.com), and shows a popup with a sound when one of your team's tiles is completed.

## Setup

1. Open your bingo on cabbingo.com and go to **Edit progress**, then **Drop setup**.
2. Copy your team's URL.
3. Paste it into the plugin's **Team URL** setting.

Nothing is sent before a Team URL is set, and nothing is sent anywhere else.

## What it does

- Posts drops, kills, pets and skilling counts that count towards one of your team's tiles. A screenshot goes along when it matters; it goes to your team's Discord channel and never shows your private chat.
- Shows a popup and plays a sound when one of your team's tiles, or its flipped side, is completed.
- Adds a board button that opens your team's bingo board in the game.
- On logout during a bingo, asks TempleOSRS to update your XP, unless RuneLite's XP Updater already does.

## Settings

| Setting | What it does |
|---|---|
| Team URL | Your team's URL from Drop setup. |
| Sound / Flipped tile sound | The sound for a completed tile or flipped side. |
| Volume | Volume of the sounds. Game sounds follow your game's sound effect volume unless it is muted. |
| Test sound / Test flipped tile sound | Plays the sound. |
| Board button | Shows the button that opens your bingo board. |
| Open board on right-click only | Left clicks go through the button to the game. |
| Hide tile details on outside click | A click outside a tile hides the picked tile's details. |
| Board tile size | The size of a tile on the board. Dragging the board's edges changes it too. |
| Board background | The colour of the board and its button. |

## Privacy

This plugin submits your IP address, your RuneScape name and your loot to a 3rd-party server not controlled or verified by the RuneLite developers.

## Building

```
./gradlew build
./gradlew run
```

## License

BSD 2-Clause, see [LICENSE](LICENSE).
