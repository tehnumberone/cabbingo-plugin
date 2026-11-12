package com.cabbingo;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The button that opens and closes the board window (BoardOverlay). A plain RuneLite overlay: Alt-drag moves it and
 * RuneLite keeps where. Shown while the team has a board.
 */
@Singleton
class BoardButton extends Overlay implements MouseListener
{
	private static final int SIZE = 36;
	private static final int CABBAGE = 1965;

	private final BoardOverlay board;
	private final ItemManager itemManager;
	private BufferedImage icon;

	@Inject
	BoardButton(BoardOverlay board, ItemManager itemManager)
	{
		this.board = board;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!board.hasBoard())
		{
			return null;
		}
		if (icon == null)
		{
			icon = itemManager.getImage(CABBAGE);
		}
		g.setColor(new Color(62, 53, 41, 230));
		g.fillRect(0, 0, SIZE, SIZE);
		g.setColor(new Color(30, 25, 20));
		g.drawRect(0, 0, SIZE - 1, SIZE - 1);
		g.setColor(new Color(90, 80, 64));
		g.drawRect(1, 1, SIZE - 3, SIZE - 3);
		g.drawImage(icon, (SIZE - icon.getWidth()) / 2, (SIZE - icon.getHeight()) / 2, null);
		return new Dimension(SIZE, SIZE);
	}

	private boolean on(MouseEvent e)
	{
		return board.hasBoard() && !e.isAltDown() && getBounds().contains(e.getPoint());
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (on(e))
		{
			if (SwingUtilities.isLeftMouseButton(e))
			{
				board.toggle();
			}
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (on(e))
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		if (on(e))
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
