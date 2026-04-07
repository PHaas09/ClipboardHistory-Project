import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ClipboardHistoryApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new ClipboardHistoryFrame().setVisible(true);
        });
    }

    enum ClipType {
        TEXT, IMAGE
    }

    static class ClipEntry implements Serializable {
        private static final long serialVersionUID = 1L;

        String id;
        ClipType type;
        String fileName;
        String preview;
        String hash;
        long createdAt;

        @Override
        public String toString() {
            String time = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(createdAt));
            if (type == ClipType.TEXT) {
                return "[Text] " + time + " - " + preview;
            }
            return "[Bild] " + time + " - " + preview;
        }
    }

    static class ImageSelection implements Transferable {
        private final Image image;

        ImageSelection(Image image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }

    static class ClipboardHistoryFrame extends JFrame {
        private static final int POLL_INTERVAL_MS = 800;
        private static final int MAX_HISTORY = 200;

        private final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final Path appDir = Paths.get(System.getProperty("user.home"), ".clipboard-history-app");
        private final Path indexFile = appDir.resolve("history.ser");

        private final DefaultListModel<ClipEntry> listModel = new DefaultListModel<>();
        private final JList<ClipEntry> historyList = new JList<>(listModel);
        private final JTextArea textArea = new JTextArea();
        private final JLabel imageLabel = new JLabel("Kein Bild ausgewählt", SwingConstants.CENTER);
        private final CardLayout previewLayout = new CardLayout();
        private final JPanel previewPanel = new JPanel(previewLayout);
        private final JLabel statusLabel = new JLabel("Bereit");

        private final List<ClipEntry> history = new ArrayList<>();
        private volatile String lastSeenHash = "";
        private volatile BufferedImage currentPreviewImage;
        private volatile boolean watcherRunning = true;

        ClipboardHistoryFrame() {
            setTitle("Clipboard History");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(980, 620);
            setLocationRelativeTo(null);

            createDirectories();
            loadHistory();
            buildUi();
            refreshList();
            if (!history.isEmpty()) {
                historyList.setSelectedIndex(0);
            }
            startWatcher();
        }

        private void createDirectories() {
            try {
                Files.createDirectories(appDir);
            } catch (IOException e) {
                showError("Ordner konnte nicht erstellt werden: " + e.getMessage());
            }
        }

        private void buildUi() {
            setLayout(new BorderLayout(10, 10));
            ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

            historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            historyList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    updatePreview(historyList.getSelectedValue());
                }
            });
            historyList.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        copySelectedBackToClipboard();
                    }
                }
            });

            JScrollPane listScroll = new JScrollPane(historyList);
            listScroll.setBorder(BorderFactory.createTitledBorder("Gespeicherte Einträge"));

            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            JScrollPane textScroll = new JScrollPane(textArea);

            imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            JScrollPane imageScroll = new JScrollPane(imageLabel);

            previewPanel.add(textScroll, "TEXT");
            previewPanel.add(imageScroll, "IMAGE");
            previewPanel.setBorder(BorderFactory.createTitledBorder("Vorschau"));

            previewPanel.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    renderCurrentImagePreview();
                }
            });

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, previewPanel);
            splitPane.setDividerLocation(380);

            JButton saveNowButton = new JButton("Jetzt speichern");
            saveNowButton.addActionListener(e -> readAndStoreClipboard(true));

            JButton restoreButton = new JButton("In Zwischenablage kopieren");
            restoreButton.addActionListener(e -> copySelectedBackToClipboard());

            JButton deleteButton = new JButton("Eintrag löschen");
            deleteButton.addActionListener(e -> deleteSelectedEntry());

            JToggleButton pauseButton = new JToggleButton("Überwachung aktiv", true);
            pauseButton.addActionListener(e -> {
                watcherRunning = pauseButton.isSelected();
                pauseButton.setText(watcherRunning ? "Überwachung aktiv" : "Überwachung pausiert");
                setStatus(watcherRunning ? "Überwachung läuft" : "Überwachung pausiert");
            });

            JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            topBar.add(saveNowButton);
            topBar.add(restoreButton);
            topBar.add(deleteButton);
            topBar.add(pauseButton);

            JPanel bottomBar = new JPanel(new BorderLayout());
            bottomBar.setBorder(new EmptyBorder(5, 0, 0, 0));
            bottomBar.add(statusLabel, BorderLayout.WEST);

            add(topBar, BorderLayout.NORTH);
            add(splitPane, BorderLayout.CENTER);
            add(bottomBar, BorderLayout.SOUTH);
        }

        private void refreshList() {
            listModel.clear();
            for (ClipEntry entry : history) {
                listModel.addElement(entry);
            }
        }

        private void startWatcher() {
            scheduler.scheduleWithFixedDelay(() -> readAndStoreClipboard(false), 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        private void readAndStoreClipboard(boolean manual) {
            if (!manual && !watcherRunning) {
                return;
            }

            try {
                Transferable content = clipboard.getContents(null);
                if (content == null) {
                    return;
                }

                if (content.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    String text = (String) content.getTransferData(DataFlavor.stringFlavor);
                    if (text == null) {
                        return;
                    }
                    String hash = "TEXT:" + sha256(text.getBytes(StandardCharsets.UTF_8));
                    if (!manual && hash.equals(lastSeenHash)) {
                        return;
                    }
                    lastSeenHash = hash;
                    if (isNewestHash(hash)) {
                        return;
                    }
                    saveTextEntry(text, hash);
                    return;
                }

                if (content.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    Image image = (Image) content.getTransferData(DataFlavor.imageFlavor);
                    if (image == null) {
                        return;
                    }
                    BufferedImage bufferedImage = toBufferedImage(image);
                    byte[] pngBytes = imageToPngBytes(bufferedImage);
                    String hash = "IMAGE:" + sha256(pngBytes);
                    if (!manual && hash.equals(lastSeenHash)) {
                        return;
                    }
                    lastSeenHash = hash;
                    if (isNewestHash(hash)) {
                        return;
                    }
                    saveImageEntry(bufferedImage, hash);
                }
            } catch (IllegalStateException ignored) {
                // Zwischenablage gerade von einem anderen Prozess gesperrt.
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> setStatus("Fehler beim Lesen der Zwischenablage: " + e.getMessage()));
            }
        }

        private boolean isNewestHash(String hash) {
            return !history.isEmpty() && hash.equals(history.get(0).hash);
        }

        private void saveTextEntry(String text, String hash) throws IOException {
            String id = createId();
            String fileName = id + ".txt";
            Files.writeString(appDir.resolve(fileName), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

            ClipEntry entry = new ClipEntry();
            entry.id = id;
            entry.type = ClipType.TEXT;
            entry.fileName = fileName;
            entry.preview = createTextPreview(text);
            entry.hash = hash;
            entry.createdAt = System.currentTimeMillis();

            addEntry(entry);
        }

        private void saveImageEntry(BufferedImage image, String hash) throws IOException {
            String id = createId();
            String fileName = id + ".png";
            ImageIO.write(image, "png", appDir.resolve(fileName).toFile());

            ClipEntry entry = new ClipEntry();
            entry.id = id;
            entry.type = ClipType.IMAGE;
            entry.fileName = fileName;
            entry.preview = image.getWidth() + "x" + image.getHeight();
            entry.hash = hash;
            entry.createdAt = System.currentTimeMillis();

            addEntry(entry);
        }

        private void addEntry(ClipEntry entry) {
            history.add(0, entry);
            while (history.size() > MAX_HISTORY) {
                ClipEntry removed = history.remove(history.size() - 1);
                deleteFileIfExists(removed);
            }
            saveHistory();
            SwingUtilities.invokeLater(() -> {
                listModel.add(0, entry);
                historyList.setSelectedIndex(0);
                setStatus("Gespeichert: " + entry);
            });
        }

        private void copySelectedBackToClipboard() {
            ClipEntry entry = historyList.getSelectedValue();
            if (entry == null) {
                setStatus("Bitte zuerst einen Eintrag auswählen.");
                return;
            }

            try {
                Path file = appDir.resolve(entry.fileName);
                if (entry.type == ClipType.TEXT) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    clipboard.setContents(new StringSelection(text), null);
                    lastSeenHash = entry.hash;
                } else {
                    BufferedImage image = ImageIO.read(file.toFile());
                    clipboard.setContents(new ImageSelection(image), null);
                    lastSeenHash = entry.hash;
                }
                setStatus("Eintrag wurde zurück in die Zwischenablage kopiert.");
            } catch (Exception e) {
                showError("Eintrag konnte nicht in die Zwischenablage kopiert werden: " + e.getMessage());
            }
        }

        private void deleteSelectedEntry() {
            int index = historyList.getSelectedIndex();
            if (index < 0) {
                setStatus("Bitte zuerst einen Eintrag auswählen.");
                return;
            }

            ClipEntry removed = history.remove(index);
            deleteFileIfExists(removed);
            saveHistory();
            listModel.remove(index);

            if (!listModel.isEmpty()) {
                historyList.setSelectedIndex(Math.min(index, listModel.size() - 1));
            } else {
                textArea.setText("");
                imageLabel.setIcon(null);
                imageLabel.setText("Keine Vorschau vorhanden");
            }

            setStatus("Eintrag gelöscht.");
        }

        private void deleteFileIfExists(ClipEntry entry) {
            try {
                Files.deleteIfExists(appDir.resolve(entry.fileName));
            } catch (IOException ignored) {
            }
        }

        private void updatePreview(ClipEntry entry) {
            if (entry == null) {
                textArea.setText("");
                imageLabel.setIcon(null);
                imageLabel.setText("Keine Vorschau vorhanden");
                currentPreviewImage = null;
                return;
            }

            try {
                Path file = appDir.resolve(entry.fileName);
                if (entry.type == ClipType.TEXT) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    textArea.setText(text);
                    textArea.setCaretPosition(0);
                    previewLayout.show(previewPanel, "TEXT");
                    currentPreviewImage = null;
                } else {
                    currentPreviewImage = ImageIO.read(file.toFile());
                    previewLayout.show(previewPanel, "IMAGE");
                    renderCurrentImagePreview();
                }
            } catch (Exception e) {
                showError("Vorschau konnte nicht geladen werden: " + e.getMessage());
            }
        }

        private void renderCurrentImagePreview() {
            if (currentPreviewImage == null) {
                imageLabel.setIcon(null);
                imageLabel.setText("Kein Bild ausgewählt");
                return;
            }

            int maxWidth = Math.max(200, previewPanel.getWidth() - 50);
            int maxHeight = Math.max(200, previewPanel.getHeight() - 80);
            Image scaled = getScaledImage(currentPreviewImage, maxWidth, maxHeight);
            imageLabel.setText(null);
            imageLabel.setIcon(new ImageIcon(scaled));
        }

        private Image getScaledImage(BufferedImage image, int maxWidth, int maxHeight) {
            double scale = Math.min((double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight());
            scale = Math.min(scale, 1.0);
            int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            return image.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        }

        @SuppressWarnings("unchecked")
        private void loadHistory() {
            if (!Files.exists(indexFile)) {
                return;
            }
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(indexFile))) {
                Object obj = in.readObject();
                if (obj instanceof List<?>) {
                    history.clear();
                    for (Object item : (List<?>) obj) {
                        if (item instanceof ClipEntry entry) {
                            if (Files.exists(appDir.resolve(entry.fileName))) {
                                history.add(entry);
                            }
                        }
                    }
                    if (!history.isEmpty()) {
                        lastSeenHash = history.get(0).hash;
                    }
                }
            } catch (Exception e) {
                showError("Historie konnte nicht geladen werden: " + e.getMessage());
            }
        }

        private void saveHistory() {
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(indexFile))) {
                out.writeObject(new ArrayList<>(history));
            } catch (IOException e) {
                showError("Historie konnte nicht gespeichert werden: " + e.getMessage());
            }
        }

        private void setStatus(String message) {
            statusLabel.setText(message);
        }

        private void showError(String message) {
            SwingUtilities.invokeLater(() -> {
                setStatus(message);
                JOptionPane.showMessageDialog(this, message, "Fehler", JOptionPane.ERROR_MESSAGE);
            });
        }

        private static String createId() {
            return System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        }

        private static String createTextPreview(String text) {
            String normalized = text.replace("\r", " ").replace("\n", " ").trim();
            if (normalized.isEmpty()) {
                return "(leerer Text)";
            }
            return normalized.length() > 70 ? normalized.substring(0, 70) + "..." : normalized;
        }

        private static BufferedImage toBufferedImage(Image image) {
            if (image instanceof BufferedImage buffered) {
                return buffered;
            }
            int width = image.getWidth(null);
            int height = image.getHeight(null);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Bildgröße konnte nicht gelesen werden.");
            }
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = bufferedImage.createGraphics();
            g2.drawImage(image, 0, 0, null);
            g2.dispose();
            return bufferedImage;
        }

        private static byte[] imageToPngBytes(BufferedImage image) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }

        private static String sha256(byte[] data) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(data);
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void dispose() {
            scheduler.shutdownNow();
            super.dispose();
        }
    }
}