import com.apple.eawt.Application;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DateFormatter;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;

import java.util.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class Schema extends JLabel {
	
	/** The number of seconds from 8:00 to 17:00 */
	private static final int SCHEME_LENGTH = 32400;
	private static final TemporalField WEEK_NUMBER_FIELD = WeekFields.ISO.weekOfWeekBasedYear();
	
	private static final String IMAGE_ERROR_MESSAGE =
			"Något gick fel, kolla på vanliga schemahemsidan och kolla om den är nere eller nåt";
	private static final String IO_ERROR_MESSAGE = "Kunde inte ansluta till internet, kolla om du är ansluten";
	private static final String INVALID_ID_MESSAGE = "Den finns inte";
	
	private static final String PROPERTIES_PATH = "prop.properties";
	private static final String ICON_PATH = "logga.png";
	
	private static final String KEY_ID = "id";
	private static final String KEY_SHOW_ID_ERROR = "showIdError";
	private static final String KEY_PREVENT_OUT_OF_BOUNDS = "prevOutOfBounds";
	
	private static final float FONT_SIZE = 16.0f;
	private static final int TEXT_SPACE = 19;
	private static final Color LINE_COLOR = new Color(0xffff0000);
	private static final int SCHEME_BG_RGB = 0xffc0c0c0;
	
	private LocalDate currentDate;
	
	/** The currently shown id that isn't stored */
	private String lastId;
	
	/** The stored id that will be returned to when pressing s */
	private String id;
	
	private final ReentrantLock schemeBufferLock = new ReentrantLock();
	private HashMap<Integer, BufferedImage> schemeBuffer;
	private BlockingQueue<int[]> loadingQueue;
	private JFrame frame;
	/** A list containing the horizontal positions of the black lines separating the days */
	private List<Integer> daySeparators;
	
	/** The scheme-relative time start position */
	private int timeStartY;
	
	private boolean showStrings;
	private int textSpace;
	
	private final Timer lineUpdateTimer = new Timer(15000, e -> repaint());
	private Properties prop;
	
	public static void main(String[] args) {
		try {
			Image icon = Toolkit.getDefaultToolkit().getImage(ICON_PATH);
			Application.getApplication().setDockIconImage(icon);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		new Schema();
	}
	
	private Schema() {
		currentDate = LocalDate.now().with(WEEK_NUMBER_FIELD, currentWeek());
		schemeBuffer = new HashMap<>();
		loadingQueue = new ArrayBlockingQueue<>(10);
		daySeparators = new ArrayList<>(6);
		textSpace = TEXT_SPACE;
		showStrings = true;
		
		prop = new Properties();
		loadProperties();
		id = prop.getProperty(KEY_ID);
		if (id == null) selectClass();
		
		new RequestThread().start();
		
		SwingUtilities.invokeLater(this::createAndShowGUI);
	}
	
	/** Creates the GUI */
	private void createAndShowGUI() {
		frame = new JFrame("Schema");
		frame.setSize(500,500);
		frame.setLocation(300,200);
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		
		com.apple.eawt.FullScreenUtilities.setWindowCanFullScreen(frame,true);
		Application application = Application.getApplication();
		
		frame.addComponentListener(new ComponentAdapter() {
			Timer timer = new Timer(50, e -> reloadBuffer());
			
			{
				timer.setRepeats(false);
			}
			
			@Override
			public void componentResized(ComponentEvent e) {
				if (timer.isRunning()) {
					timer.restart();
				} else timer.start();
			}
		});
		
		InputMap im = this.getInputMap();
		im.put(KeyStroke.getKeyStroke("RIGHT"), "nextWeek");
		im.put(KeyStroke.getKeyStroke("LEFT"), "lastWeek");
		im.put(KeyStroke.getKeyStroke(' '), "currentWeek");
		im.put(KeyStroke.getKeyStroke("ESCAPE"), "exit");
		im.put(KeyStroke.getKeyStroke("F"), "toggleFullscreen");
		im.put(KeyStroke.getKeyStroke("ctrl meta F"), "toggleFullscreen");
		im.put(KeyStroke.getKeyStroke("K"), "selectClass");
		im.put(KeyStroke.getKeyStroke("meta R"), "reloadBuffer");
		im.put(KeyStroke.getKeyStroke("S"), "swap");
		im.put(KeyStroke.getKeyStroke("L"), "emptyClassrooms");
		im.put(KeyStroke.getKeyStroke("H"), "hideStrings");
		
		ActionMap am = this.getActionMap();
		am.put("nextWeek", new SetWeekAction(true));
		am.put("lastWeek", new SetWeekAction(false));
		am.put("currentWeek", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				currentDate = LocalDate.now().with(WEEK_NUMBER_FIELD, currentWeek());
				loadingQueue.offer(new int[]{0,0,0});
				repaint();
			}
		});
		am.put("exit", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				System.exit(0);
			}
		});
		am.put("toggleFullscreen", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				application.requestToggleFullScreen(frame);
			}
		});
		am.put("selectClass", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				selectClass();
			}
		});
		am.put("reloadBuffer", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				reloadBuffer();
			}
		});
		am.put("swap", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (lastId != null) {
					showId(lastId);
					storeId(id);
				}
			}
		});
		am.put("emptyClassrooms", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				findEmptyClassrooms();
			}
		});
		am.put("hideStrings", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				toggleHide();
			}
		});
		
		this.setFont(getFont().deriveFont(FONT_SIZE));
		
		Toolkit.getDefaultToolkit().setDynamicLayout(false);
		
		frame.add(this);
		frame.setVisible(true);
		
		lineUpdateTimer.start();
	}
	
	private void toggleHide() {
		showStrings ^= true;
		textSpace ^= TEXT_SPACE;
		reloadBuffer();
		repaint();
	}
	
	/** Reloads the buffer. Should be called on resize or class change */
	private void reloadBuffer() {
		loadingQueue.offer(new int[]{0,0});
	}
	
	private void selectClass() {
		String id;
		for (;;) { // fortsätt att loopa så länge klassen är invalid
			id = JOptionPane.showInputDialog(
					frame,
					"Skriv in en id, lärare eller klassrum",
					"Välj id",
					JOptionPane.PLAIN_MESSAGE);
			
			if (id == null) {
				if (this.isShowing()) return;
				else System.exit(0);
			}
			
			BufferedImage testScheme = getScheme(id, currentWeek(), 0,60, 20);
			
			if (isInvalidScheme(testScheme)) {
				if (Boolean.parseBoolean(prop.getProperty(KEY_SHOW_ID_ERROR, "false"))) 
					JOptionPane.showMessageDialog(
							frame,
							INVALID_ID_MESSAGE,
							"Invalid id",
							JOptionPane.ERROR_MESSAGE);
				
				continue;
			}
			
			break;
		}
		
		showId(id);
		storeId(id);
	}
	
	private void showId(String id) {
		this.lastId = this.id;
		this.id = id;
		if (this.isShowing()) reloadBuffer();
	}
	
	/** Loads the class. If the cfg file hasn't been created it will create a new file */
	private void loadProperties() {
		FileInputStream in = null;
		try {
			in = new FileInputStream(PROPERTIES_PATH);
			prop.load(in);
		} catch (IOException e) {
			selectClass();
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	private void storeId(String id) {
		store(KEY_ID, id);
	}
	
	private void store(String key, String value) {
		FileOutputStream out = null;
		try {
			out = new FileOutputStream(PROPERTIES_PATH);
			
			prop.setProperty(key, value);
			prop.store(out, null);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (out != null) {
				try {
					out.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	/** Shows the scheme for week <tt>week</tt>, assuming it's in the buffer */
	private void setSchemeToWeek(int week) {
		currentDate = currentDate.with(WEEK_NUMBER_FIELD, week);
		repaint();
	}
	
	/** Gets an image containing the scheme for week <tt>week</tt> */
	private BufferedImage getSchemeForWeek(int week) {
		if (schemeBuffer.containsKey(week)) return schemeBuffer.get(week);
		BufferedImage res = getScheme(id, week, 0,this.getWidth() + 2, this.getHeight() - textSpace + 1);
		
		if (res == null) {
			frame.dispose();
			
			JOptionPane.showMessageDialog(
					frame,
					IMAGE_ERROR_MESSAGE,
					"Kunde inte läsa schemat",
					JOptionPane.ERROR_MESSAGE);
			
			System.exit(0);
			return null;
			
		} else
			return res;
	}
	
	private static final List<String> CLASSROOMS = Arrays.asList(
			"B212", "B213", "B215", "B216", "B218a", "B218b",
			"B305", "B306", "B308", "B309", "B311a", "B311b",
			"B404", "B406", "B408", "B409", "B411a", "B411b",
			"D101", "D102", "D202"
	);
	
	private void findEmptyClassrooms() {
		int tempDay = getDayOfWeekIndex();
		if (tempDay > 4) return;
		tempDay = 1 << tempDay;
		int day = tempDay;
		LocalTime relativeSchemeTime = LocalDateTime.now().toLocalTime().minusHours(8);
		
		do {
			if (relativeSchemeTime.get(ChronoField.HOUR_OF_DAY) > 9)
				return;
			
			LocalTime schemeTime = relativeSchemeTime.plusHours(8);
			
			JOptionPane pane = new JOptionPane(
					"Vänta...",
					JOptionPane.INFORMATION_MESSAGE,
					JOptionPane.DEFAULT_OPTION,
					null,
					new String[]{"Avbryt"},
					"Avbryt");
			
			JDialog loadingDialog = pane.createDialog(frame,"Lediga klassrum");
			loadingDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			
			int schemeRelativeY = getSchemeRelativeY(relativeSchemeTime, 400, 23);
			List<String> emptyClassrooms = new ArrayList<>();
			
			new SwingWorker<Void, Void>() {
				@Override
				protected Void doInBackground() {
					for (String classroom : CLASSROOMS) {
						BufferedImage scheme =
								getScheme(
										classroom,
										currentWeek(), day,
										40, 400);
						
						if (scheme.getRGB(1, schemeRelativeY) == SCHEME_BG_RGB)
							emptyClassrooms.add(classroom);
					}
					
					return null;
				}
				
				@Override
				protected void done() { // close it fancily
					loadingDialog.dispatchEvent(new WindowEvent(loadingDialog, WindowEvent.WINDOW_CLOSING));
				}
			}.execute();
			loadingDialog.setVisible(true);
			
			Object selectedValue = pane.getValue();
			if (selectedValue != null && selectedValue.toString().equals("Avbryt")) {
				return;
			}
			
			JEditorPane ep = formatClassroomList(emptyClassrooms);
			ep.addHyperlinkListener(e -> {
				if (e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED)) {
					showId(e.getDescription());
				}
			});
			
			pane = new JOptionPane(
					ep,
					JOptionPane.INFORMATION_MESSAGE,
					JOptionPane.DEFAULT_OPTION,
					null,
					new String[]{"OK", "Lediga kl...", "Lediga om 20 min"},
					"OK");
			
			JDialog dialog = pane.createDialog(frame, "Lediga klassrum " 
					+ schemeTime.format(DateTimeFormatter.ofPattern("HH:mm")));
			dialog.setVisible(true);
			String optionSelected = ((String) pane.getValue());
			if (optionSelected == null || optionSelected.equals("OK")) {
				return; // man trycker på OK
			} else if (optionSelected.equals("Lediga kl...")) {
				while (true) {
					SpinnerDateModel model = new SpinnerDateModel();
					model.setValue(Date.from(LocalDateTime.now().toInstant(ZoneOffset.UTC)));
					
					JSpinner spinner = new JSpinner(model);
					JSpinner.DateEditor editor = new JSpinner.DateEditor(spinner, "HH:mm");
					
					DateFormatter dateFormatter = ((DateFormatter) editor.getTextField().getFormatter());
//				dateFormatter.setAllowsInvalid(false);
					dateFormatter.setOverwriteMode(true);
					
					spinner.setEditor(editor);
					
					pane = new JOptionPane(spinner);
					
					pane.createDialog(frame, "Välj tid").setVisible(true);
					String value = spinner.getValue().toString();
//				System.out.println(value);
					
					// detta är hemskt, jag vet
					try {
						relativeSchemeTime = 
								LocalTime.of(
										Integer.parseInt(value.substring(11, 13)), 
										Integer.parseInt(value.substring(14, 16))
								).minusHours(8);
					} catch (Exception e) {
						e.printStackTrace();
						continue;
					}
					
					break;
				}
				
			} else relativeSchemeTime = relativeSchemeTime.plusMinutes(20);
			
		} while (true);
	}
	
	private JEditorPane formatClassroomList(List<String> classroooms) {
		StringBuilder b2 = new StringBuilder("Våning 2: ");
		StringBuilder b3 = new StringBuilder("Våning 3: ");
		StringBuilder b4 = new StringBuilder("Våning 4: ");
		StringBuilder d_ = new StringBuilder("Baracken: ");
		
		for (String classroom : classroooms) {
			classroom += " ";
			
			String substr = classroom.substring(0, 2);
			switch (substr) {
				case "B2": b2.append(classroom); break;
				case "B3": b3.append(classroom); break;
				case "B4": b4.append(classroom); break;
				default: d_.append(classroom); break; // annars är det på d nånting
			}
		}
		
		JLabel label = new JLabel();
		Font font = label.getFont();
		
		String style = "font-family:" + font.getFamily() + ";"
				+ "font-weight:" + (font.isBold() ? "bold" : "normal") + ";"
				+ "font-size:" + font.getSize() + "pt;";
		
		JEditorPane ep = new JEditorPane(
				"text/html",
				"<html><body style=\"" + style + "\">"
						+ String.format("%s<br>%s<br>%s<br>%s", b4, b3, b2, d_).replaceAll(
								"([A-Z]\\d+\\w*)", "<a href=$1>$1</a>")
						+ "</body></html>");
		
		ep.setEditable(false);
		ep.setBackground(label.getBackground());
		
		return ep;
	}
	
	/**
	 * Gets the y position of where the current time is relative to the scheme
	 *
	 * @param schemeRelativeTime the current time but subtracting 8 so the time starts when the scheme starts
	 * @param schemeHeight the height of the current scheme
	 * @param timeStartY the y position where the date label ends and the scheduled lessons begin
	 */
	private int getSchemeRelativeY(LocalTime schemeRelativeTime, int schemeHeight, int timeStartY) {
		return (schemeHeight - timeStartY - 1) * schemeRelativeTime.toSecondOfDay() / SCHEME_LENGTH + timeStartY;
	}
	
	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2d = (Graphics2D) g;
		
		int week = shownWeekNumber();
		if (showStrings) {
			String weekString = "V." + week;
			
			int weekDiff = weeksBetween(LocalDate.now(), currentDate);
			if (weekDiff != 0)
				weekString += String.format(" (%s%d)", (weekDiff > 0 ? "+" : ""), weekDiff);
			
			g2d.setRenderingHint(
					RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
			
			FontMetrics fm = g2d.getFontMetrics();
			int stringHeight = ((textSpace - fm.getHeight()) / 2) + fm.getAscent();
			
			g2d.drawString(
					weekString,
					(getWidth() - fm.stringWidth(weekString)) / 2,
					stringHeight);
			
			g2d.drawString(
					id,
					(getWidth() - fm.stringWidth(id)) / 4,
					stringHeight);
		}
		
		BufferedImage scheme = schemeBuffer.get(week);
		
		if (scheme == null)
			return;
		
		g2d.drawImage(scheme, 0, textSpace, null);
		
		if (week != currentWeek()) 
			return;
		
		int dayOfWeek = getDayOfWeekIndex();
		if (dayOfWeek >= 5) 
			return;
		
		LocalTime relativeSchemeTime = LocalDateTime.now().toLocalTime().minusHours(8);
		if (relativeSchemeTime.get(ChronoField.HOUR_OF_DAY) >= 9) 
			return;
		
		g2d.setColor(LINE_COLOR);
		g2d.setStroke(new BasicStroke(1));
		
		int x = daySeparators.get(dayOfWeek) + 1;
		
		int schemeRelativeY = getSchemeRelativeY(relativeSchemeTime, scheme.getHeight(), timeStartY);
		int frameRelativeY = schemeRelativeY + textSpace;
		
		int bgRGB = scheme.getRGB(x, schemeRelativeY); // gets the background rgb
		
		for (; x <= daySeparators.get(dayOfWeek + 1) - 1; x++) { // draw the red line where the colour isn't bgRGB
			int posRGB = scheme.getRGB(x, schemeRelativeY);
			if (posRGB == bgRGB || posRGB == SCHEME_BG_RGB) {
				g.drawRect(x, frameRelativeY, 0, 0);
			}
		}
	}
	
	private static int weeksBetween(LocalDate start, LocalDate end) {
		start = start.with(ChronoField.DAY_OF_WEEK, 1);
		end = end.with(ChronoField.DAY_OF_WEEK, 1);
		
		if (start.isAfter(end))
			return -weeksBetween(end, start);
		
		int weeks = 0;
		LocalDate temp = start;
		
		// while temp is before or is end, add another week
		while (temp.isBefore(end)) {
			temp = temp.plusWeeks(1);
			weeks++;
		}
		
		return weeks;
	}
	
	private static final String FORMATTING_STRING =
			"http://www.novasoftware.se/ImgGen/schedulegenerator.aspx?format=png&schoolid=29120/sv-se&id=%s&period=&week=%d&day=%d&width=%d&height=%d";
	
	private BufferedImage getScheme(String klass, int week, int day, int width, int height) {
		while (true) {
			try {
				return ImageIO.read(
						new URL(String.format(
								FORMATTING_STRING, 
								klass.replace(" ", "%20"), 
								week, day, width, height)));
				
			} catch (IOException e) {
				int optionChosen = JOptionPane.showOptionDialog(
						frame,
						IO_ERROR_MESSAGE,
						"Kunde inte ansluta",
						JOptionPane.DEFAULT_OPTION,
						JOptionPane.ERROR_MESSAGE,
						null,
						new String[]{"OK", "Försök igen"},
						"OK"
				);
				
				if (optionChosen == 0) break;
			}
		}
		
		System.exit(0);
		throw new RuntimeException();
	}
	
	private int shownWeekNumber() {
		return weekOfDate(currentDate);
	}
	
	private static int weekOfDate(LocalDate localDate) {
		return localDate.get( WeekFields.ISO.weekOfWeekBasedYear() );
	}
	
	/** 
	 * Gets the day of the current week with 0 being monday,
	 * all the way up to sunday being 6
	 */
	private static int getDayOfWeekIndex() {
		return LocalDate.now().getDayOfWeek().getValue() - 1;
	}
	
	private static int currentWeek() {
		return (LocalDate.now().plusDays(2).get( WeekFields.of(Locale.getDefault() ).weekOfWeekBasedYear()));
	}
	
	private static boolean isInvalidScheme(BufferedImage scheme) {
		return (scheme.getRGB(0, 0) ^ scheme.getRGB(0, 1)) == 0x00ffffcc;
	}
	
	class SetWeekAction extends AbstractAction {
		
		boolean inc;
		
		SetWeekAction(boolean inc) {
			this.inc = inc;
		}
		
		@Override
		public void actionPerformed(ActionEvent e) {
			
			LocalDate showWeek;
			LocalDate loadWeek = currentDate;
			
			if (inc) {
				showWeek = currentDate.plusWeeks(1);
				loadWeek = loadWeek.plusWeeks(2);
			} else {
				showWeek = currentDate.minusWeeks(1);
				loadWeek = loadWeek.minusWeeks(2);
			}
			
			if (Boolean.parseBoolean(prop.getProperty(KEY_PREVENT_OUT_OF_BOUNDS, "true"))) {
				BufferedImage img;
				do {
					img = schemeBuffer.get(weekOfDate(showWeek));
					if (img != null) break;
					try {
						schemeBufferLock.lock();
					} finally {
						schemeBufferLock.unlock();
					}
				} while (true);
				
				if (isInvalidScheme(img)) return;
			}
			
			currentDate = showWeek;
			setSchemeToWeek(weekOfDate(showWeek));
			
			loadingQueue.offer(new int[]{ weekOfDate(loadWeek) });
		}
	}
	
	class RequestThread extends Thread {
		@Override
		public void run() {
			//noinspection InfiniteLoopStatement
			for (;;) {
				try {
					int[] request = loadingQueue.take();
					
					schemeBufferLock.lock();
					
					// if there is more than 1 argument, resize instead of showing the week
					if (request.length > 1) {
						if (request.length == 2) schemeBuffer.clear();
						reloadBuffer();
						continue;
					}
					
					int loadWeek = request[0];
					
					if (!schemeBuffer.containsKey(loadWeek))
						schemeBuffer.put(loadWeek, getSchemeForWeek(loadWeek));
					
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					schemeBufferLock.unlock();
				}
				
				repaint();
			}
			
		}
		
		private void reloadBuffer() {
			BufferedImage currentScheme = getSchemeForWeek(shownWeekNumber());
			
			int nextWeek = weekOfDate(currentDate.plusWeeks(1));
			int lastWeek = weekOfDate(currentDate.minusWeeks(1));
			
			schemeBuffer.put(shownWeekNumber(), currentScheme);
			schemeBuffer.put(nextWeek, getSchemeForWeek(nextWeek));
			schemeBuffer.put(lastWeek, getSchemeForWeek(lastWeek));
			
			{
				int x = getWidth() / 5 - 2;
				
				//noinspection ConstantConditions
				for (int y = 2; y < currentScheme.getHeight(); y++) {
					if (currentScheme.getRGB(x, y) == 0xff000000) {
						timeStartY = y;
						break;
					}
				}
			}
			
			// get the x position of the lines separating the weeks
			daySeparators.clear();
			
			int y = 2;
			for (int x = 0; x < currentScheme.getWidth(); x++) {

				if (currentScheme.getRGB(x, y) == 0xff000000)
					daySeparators.add(x);
				
			}
			
			repaint();
		}
	}
}
