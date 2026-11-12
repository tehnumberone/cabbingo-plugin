package com.cabbingo;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The button that opens and closes the board window (BoardOverlay). A plain RuneLite overlay: Alt-drag moves it and
 * RuneLite keeps where. Shown while the team has a board, unless turned off in the config. Drawn under the game's
 * interfaces: one over it gets the clicks (covered). The plugin adds "Open Bingo board" to its right-click menu; with
 * "Open board on right-click only" a left click goes through it to the game.
 */
@Singleton
class BoardButton extends Overlay implements MouseListener
{
	private static final int SIZE = 36;
	private static final int CABBAGE = 1965;

	private final BoardOverlay board;
	private final ItemManager itemManager;
	private final CabbingoConfig config;
	private BufferedImage icon;
	private java.awt.Rectangle drawn; // the cabbage's own pixels in its picture (the picture has room around it); null until loaded
	volatile boolean covered; // a game interface lies over it (the plugin's menu check)
	private boolean ownPress; // the last press was taken here: its release and click are too, no other

	@Inject
	BoardButton(BoardOverlay board, ItemManager itemManager, CabbingoConfig config)
	{
		this.board = board;
		this.itemManager = itemManager;
		this.config = config;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!board.hasBoard() || !config.boardButton())
		{
			return null;
		}
		if (icon == null)
		{
			icon = itemManager.getImage(CABBAGE);
		}
		g.setColor(config.background());
		g.fillRect(0, 0, SIZE, SIZE);
		if (!frame(g))
		{
			g.setColor(new Color(30, 25, 20));
			g.drawRect(0, 0, SIZE - 1, SIZE - 1);
			g.setColor(new Color(90, 80, 64));
			g.drawRect(1, 1, SIZE - 3, SIZE - 3);
		}
		if (drawn == null)
		{
			drawn = opaque(icon);
		}
		java.awt.Rectangle c = drawn == null ? new java.awt.Rectangle(icon.getWidth(), icon.getHeight()) : drawn;
		g.drawImage(icon, (SIZE - c.width) / 2 - c.x, (SIZE - c.height) / 2 - c.y, null);
		return new Dimension(SIZE, SIZE);
	}

	// The game's stone button frame (or a resource pack's): corners as they are, the edges stretched between them. False
	// while the game has not got the sprites.
	private boolean frame(Graphics2D g)
	{
		BufferedImage tl = board.sprite(SpriteID.V2StoneButton.TOP_LEFT), tr = board.sprite(SpriteID.V2StoneButton.TOP_RIGHT),
			bl = board.sprite(SpriteID.V2StoneButton.BOTTOM_LEFT), br = board.sprite(SpriteID.V2StoneButton.BOTTOM_RIGHT),
			l = board.sprite(SpriteID.V2StoneButton.LEFT), t = board.sprite(SpriteID.V2StoneButton.TOP),
			r = board.sprite(SpriteID.V2StoneButton.RIGHT), b = board.sprite(SpriteID.V2StoneButton.BOTTOM);
		if (tl == null || tr == null || bl == null || br == null || l == null || t == null || r == null || b == null)
		{
			return false;
		}
		g.drawImage(t, tl.getWidth(), 0, SIZE - tl.getWidth() - tr.getWidth(), t.getHeight(), null);
		g.drawImage(b, bl.getWidth(), SIZE - b.getHeight(), SIZE - bl.getWidth() - br.getWidth(), b.getHeight(), null);
		g.drawImage(l, 0, tl.getHeight(), l.getWidth(), SIZE - tl.getHeight() - bl.getHeight(), null);
		g.drawImage(r, SIZE - r.getWidth(), tr.getHeight(), r.getWidth(), SIZE - tr.getHeight() - br.getHeight(), null);
		g.drawImage(tl, 0, 0, null);
		g.drawImage(tr, SIZE - tr.getWidth(), 0, null);
		g.drawImage(bl, 0, SIZE - bl.getHeight(), null);
		g.drawImage(br, SIZE - br.getWidth(), SIZE - br.getHeight(), null);
		return true;
	}

	// The box around a picture's visible pixels; null while it is all clear (an item picture that is still loading)
	static java.awt.Rectangle opaque(BufferedImage img)
	{
		java.awt.Rectangle box = null;
		for (int py = 0; py < img.getHeight(); py++)
		{
			for (int px = 0; px < img.getWidth(); px++)
			{
				if ((img.getRGB(px, py) >>> 24) != 0)
				{
					if (box == null)
					{
						box = new java.awt.Rectangle(px, py, 1, 1);
					}
					else
					{
						box.add(new java.awt.Rectangle(px, py, 1, 1));
					}
				}
			}
		}
		return box;
	}

	// the button is shown here and not under an interface
	boolean hovered(int px, int py)
	{
		return board.hasBoard() && config.boardButton() && !covered && getBounds().contains(px, py);
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		// only a left click, and not with right-click only: a right click opens the menu, the middle button turns the camera
		ownPress = hovered(e.getX(), e.getY()) && !config.boardRightClick() && !e.isAltDown() && SwingUtilities.isLeftMouseButton(e);
		if (ownPress)
		{
			board.toggle();
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (ownPress && SwingUtilities.isLeftMouseButton(e))
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		if (ownPress && SwingUtilities.isLeftMouseButton(e))
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e)
	{
		return e;
	}
}
