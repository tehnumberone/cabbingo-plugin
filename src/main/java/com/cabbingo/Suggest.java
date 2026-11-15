package com.cabbingo;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Suggestions under a text field while typing, as the site's tile editor has (SelfPanel: item names from the game,
 * bosses from the hiscores): names that start with the text first, then names that hold it, at most 8.
 */
final class Suggest
{
	private static final int MAX = 8;
	private static final String PICKED = "cabbingo.suggestPicked";

	private Suggest()
	{
	}

	static void attach(JTextField field, Supplier<List<String>> names, Consumer<String> picked)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.setFocusable(false); // (the typing stays in the field)
		menu.setBackground(SelfPanel.SURFACE);
		menu.setBorder(javax.swing.BorderFactory.createLineBorder(SelfPanel.EDGE_HI));
		field.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				SwingUtilities.invokeLater(() -> show(field, menu, names.get(), picked));
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				SwingUtilities.invokeLater(() -> show(field, menu, names.get(), picked));
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
			}
		});
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				menu.setVisible(false);
			}
		});
	}

	private static void show(JTextField field, JPopupMenu menu, List<String> names, Consumer<String> picked)
	{
		menu.setVisible(false);
		menu.removeAll();
		String q = field.getText().trim().toLowerCase(Locale.ROOT);
		// (the picked name set into the field does not open the list again)
		if (q.length() < 2 || !field.isFocusOwner() || q.equalsIgnoreCase(String.valueOf(field.getClientProperty(PICKED))))
		{
			return;
		}
		List<String> found = Stream.concat(
			names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(q)),
			names.stream().filter(n -> !n.toLowerCase(Locale.ROOT).startsWith(q) && n.toLowerCase(Locale.ROOT).contains(q))
		).limit(MAX).collect(Collectors.toList());
		// a full name keeps the longer ones in view ("Chinchompa": Red chinchompa, Black chinchompa); only the name itself left closes it
		if (found.isEmpty() || found.size() == 1 && found.get(0).equalsIgnoreCase(q))
		{
			return;
		}
		for (String name : found)
		{
			JMenuItem item = new JMenuItem(name);
			item.setBackground(SelfPanel.SURFACE);
			item.setForeground(java.awt.Color.WHITE);
			item.addActionListener(e ->
			{
				menu.setVisible(false);
				field.putClientProperty(PICKED, name);
				picked.accept(name);
			});
			menu.add(item);
		}
		menu.show(field, 0, field.getHeight());
	}
}
