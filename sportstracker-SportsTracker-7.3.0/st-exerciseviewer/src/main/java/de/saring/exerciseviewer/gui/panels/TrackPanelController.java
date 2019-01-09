package de.saring.exerciseviewer.gui.panels;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingNode;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.jxmapviewer.JXMapKit;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.GeoPosition;

import de.saring.exerciseviewer.data.EVExercise;
import de.saring.exerciseviewer.data.ExerciseSample;
import de.saring.exerciseviewer.data.Lap;
import de.saring.exerciseviewer.data.Position;
import de.saring.exerciseviewer.gui.EVContext;
import de.saring.exerciseviewer.gui.EVDocument;
import de.saring.util.unitcalc.FormatUtils;

/**
 * Controller (MVC) class of the "Track" panel, which displays the recorded location data of
 * the exercise (if available) in a map.<br/>
 * The map component is JXMapKit from the SwingLabs project, the data provider is OpenStreetMap.
 *
 * TODO use a JavaFX based map viewer component instead of JXMapKit!
 *
 * @author Stefan Saring
 */
public class TrackPanelController extends AbstractPanelController {

    private static final Color COLOR_START = new Color(180, 255, 180);
    private static final Color COLOR_END = new Color(255, 180, 180);
    private static final Color COLOR_POSITION = new Color(215, 110, 240);
    private static final Color COLOR_LAP = Color.WHITE;
    private static final Color COLOR_TRACK = Color.RED;

    private static final int TRACKPOINT_TOOLTIP_DISTANCE_BUFFER = 4;

    @FXML
    private StackPane spTrackPanel;

    @FXML
    private VBox vbTrackViewer;

    @FXML
    private StackPane spMapViewer;

    @FXML
    private Slider slPosition;

    private Tooltip spMapViewerTooltip;

    private SwingNode snMapViewer;

    private JXMapKit mapKit;

    private MouseMotionAdapter mouseMotionListener;

    /** Flag whether the exercise track has already been shown. */
    private boolean showTrackExecuted = false;

    /**
     * Standard c'tor for dependency injection.
     *
     * @param context the ExerciseViewer UI context
     * @param document the ExerciseViewer document / model
     */
    public TrackPanelController(final EVContext context, final EVDocument document) {
        super(context, document);
    }

    /**
     * Cleanup of the JXMapViewer component, if it is displayed. Needs to be called when the
     * dialog is closed, otherwise there will be memory leaks. Normally there is nothing to do,
     * but here are problems which are probably caused by the Swing -> JavaFX integration.
     */
    public void cleanupPanel() {
        if (mapKit != null) {
            spMapViewer.getChildren().clear();
            spMapViewer = null;
            snMapViewer.setContent(null);
            snMapViewer = null;

            javax.swing.SwingUtilities.invokeLater(() -> {
                // remove relations to this controller, otherwise the GC can't remove this Panel and all EV components
                mapKit.getMainMap().removeMouseMotionListener(mouseMotionListener);
                mapKit.getMainMap().setOverlayPainter(null);

                // dispose both TileFactory instances, otherwise the GC can't remove all the MapViewer objects
                mapKit.getMainMap().getTileFactory().dispose();
                mapKit.getMiniMap().getTileFactory().dispose();
                mapKit = null;
            });
        }
    }

    @Override
    protected String getFxmlFilename() {
        return "/fxml/panels/TrackPanel.fxml";
    }

    @Override
    protected void setupPanel() {

        // if track data is available: setup the map viewer and display it in a SwingNode
        final EVExercise exercise = getDocument().getExercise();
        if (exercise.getRecordingMode().isLocation()) {
            setupMapViewerContainer();
            setupMapViewerTooltip();
            setupTrackPositionSlider();
            javax.swing.SwingUtilities.invokeLater(this::setupSwingMapViewer);

        } else {
            // remove the track viewer VBox, the StackPane now displays the label "No track data available")
            spTrackPanel.getChildren().remove(vbTrackViewer);
        }
    }

    private void setupMapViewerContainer() {
        snMapViewer = new SwingNode();
        spMapViewer.getChildren().add(0, snMapViewer);

        // force repaint of Swing-based map viewer on each window size change, otherwise
        // the map viewer can be invisible or the size does not match its parent (JavaFX 8u40)
        final ChangeListener<Number> resizeListener = (observable, oldValue, newValue) -> {
            javax.swing.SwingUtilities.invokeLater(() -> {
                mapKit.repaint();
            });
        };
        spMapViewer.widthProperty().addListener(resizeListener);
        spMapViewer.heightProperty().addListener(resizeListener);
    }

    private void setupSwingMapViewer() {
        mapKit = new JXMapKit();
        mapKit.setDefaultProvider(JXMapKit.DefaultProviders.OpenStreetMaps);

        // add MouseMotionListener to the map for nearby sample lookup and tooltip creation
        mouseMotionListener = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                lookupNearbySampleAndCreateToolTip(e);
            }
        };
        mapKit.getMainMap().addMouseMotionListener(mouseMotionListener);

        // map viewer will be visible after the track has been displayed (otherwise the user
        // sees the world map for a short time before the track of the exercise)
        mapKit.setVisible(false);
        snMapViewer.setContent(mapKit);
    }

    private void setupMapViewerTooltip() {
        // create the JavaFX tooltip to be shown on the StackPane containing the Swing-based map viewer
        // (Swing tooltips have a completely different look & feel)
        spMapViewerTooltip = new Tooltip();
        spMapViewerTooltip.setAutoHide(true);
    }

    private void setupTrackPositionSlider() {
        slPosition.setMax(getDocument().getExercise().getSampleList().length - 1);

        // on track position slider changes: update position marker in the map viewer and display tooltip with details
        slPosition.valueProperty().addListener((observable, oldValue, newValue) -> {
            // slider value is a double, make sure the int value has changed
            if (oldValue.intValue() != newValue.intValue()) {

                final String tooltipText = createToolTipText(newValue.intValue());
                spMapViewerTooltip.setText(tooltipText);

                // display position tooltip in the upper left corner of the map viewer container
                Point2D tooltipPos = spMapViewer.localToScene(8d, 8d);
                tooltipPos = tooltipPos.add(getMapViewerScreenPosition());
                spMapViewerTooltip.show(spMapViewer, tooltipPos.getX(), tooltipPos.getY());

                javax.swing.SwingUtilities.invokeLater(() -> {
                    mapKit.repaint();
                });
            }
        });
    }

    /**
     * Displays the track of the current exercise, if available. This method will be executed only
     * once and should be called when the user wants to see the track (to prevent long startup delays).
     */
    public void showTrack() {
        if (!showTrackExecuted) {
            showTrackExecuted = true;

            EVExercise exercise = getDocument().getExercise();
            if (exercise.getRecordingMode().isLocation()) {

                javax.swing.SwingUtilities.invokeLater(() -> {
                    List<GeoPosition> sampleGeoPositions = createSampleGeoPositionList(exercise);
                    List<GeoPosition> lapGeoPositions = createLapGeoPositionList(exercise);

                    if (!sampleGeoPositions.isEmpty()) {
                        // setup map zoom and position
                        setupZoomAndCenterPosition(sampleGeoPositions);
                        // display track
                        setupTrackPainter(sampleGeoPositions, lapGeoPositions);
                    }
                    mapKit.setVisible(true);
                });
            }
        }
    }

    /**
     * Sets the zoom level and map center position. The full track will be visible
     * with as much details as possible.
     * This implementations is a workaround for a bug in JXMapViewer.calculateZoomFrom(),
     * which should do the same.
     *
     * @param positions list of positions of the route
     */
    private void setupZoomAndCenterPosition(List<GeoPosition> positions) {

        // calculate and set center position of the track
        Rectangle2D gpRectangle = createGeoPositionRectangle(positions);
        GeoPosition gpCenter = new GeoPosition(gpRectangle.getCenterX(), gpRectangle.getCenterY());
        mapKit.setCenterPosition(gpCenter);

        // calculate mapKit dimensions based on the Track StackPane dimensions (with a little offset)
        // (there's a bug in SwingNode.getLayoutBounds() and so in JXMapKit.getWidth()/getHeight())
        Bounds mapViewerBounds = spTrackPanel.getLayoutBounds();
        int mapKitWidth = (int) mapViewerBounds.getWidth() - 30;
        int mapKitHeight = (int) mapViewerBounds.getHeight() - 30;

        // start with zoom level for maximum details
        boolean fullTrackVisible = false;
        int currentZoom = 0;
        int maxZoom = mapKit.getMainMap().getTileFactory().getInfo().getMaximumZoomLevel();

        // stop when the track is completely visible or when the max zoom level has been reached
        while (!fullTrackVisible && currentZoom < maxZoom) {
            currentZoom++;
            mapKit.setZoom(currentZoom);

            // calculate pixel positions of top left and bottom right in the track rectangle
            java.awt.geom.Point2D ptTopLeft = convertGeoPosToPixelPos( //
                    new GeoPosition(gpRectangle.getX(), gpRectangle.getY()));
            java.awt.geom.Point2D ptBottomRight = convertGeoPosToPixelPos(new GeoPosition(
                    gpRectangle.getX() + gpRectangle.getWidth(), gpRectangle.getY() + gpRectangle.getHeight()));

            // calculate current track width and height in pixels (can be negative)
            int trackPixelWidth = Math.abs((int) (ptBottomRight.getX() - ptTopLeft.getX()));
            int trackPixelHeight = Math.abs((int) (ptBottomRight.getY() - ptTopLeft.getY()));

            // track is completely visible when track dimensions are smaller than map viewer dimensions
            fullTrackVisible = trackPixelWidth < mapKitWidth && trackPixelHeight < mapKitHeight;
        }
    }

    /**
     * Creates a rectangle of minimal size which contains all specified GeoPositions.
     *
     * @param positions list of positions of the route
     * @return the created Rectangle
     */
    private Rectangle2D createGeoPositionRectangle(List<GeoPosition> positions) {
        Rectangle2D rect = new Rectangle2D.Double(positions.get(0).getLatitude(), positions.get(0).getLongitude(), 0, 0);

        for (GeoPosition pos : positions) {
            rect.add(new java.awt.geom.Point2D.Double(pos.getLatitude(), pos.getLongitude()));
        }
        return rect;
    }

    /**
     * Creates a custom painter which draws the track.
     *
     * @param sampleGeoPositions list of GeoPosition objects of all samples of this track
     * @param lapGeoPositions list of GeoPosition objects of all lap splits this track
     */
    private void setupTrackPainter(final List<GeoPosition> sampleGeoPositions, final List<GeoPosition> lapGeoPositions) {

        Painter<JXMapViewer> lineOverlay = new Painter<JXMapViewer>() {
            public void paint(Graphics2D g, JXMapViewer map, int w, int h) {

                g = (Graphics2D) g.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // convert from viewport to world bitmap
                Rectangle rect = mapKit.getMainMap().getViewportBounds();
                g.translate(-rect.x, -rect.y);

                // draw track line
                drawTrackLine(g, sampleGeoPositions);

                // draw waypoints for all lap split positions
                for (int i = 0; i < lapGeoPositions.size(); i++) {
                    GeoPosition geoPosition = lapGeoPositions.get(i);
                    drawWaypoint(g, geoPosition, String.valueOf(i + 1), COLOR_LAP);
                }

                // draw waypoints for start and end position
                drawWaypoint(g, sampleGeoPositions.get(0), "S", COLOR_START);
                drawWaypoint(g, sampleGeoPositions.get(sampleGeoPositions.size() - 1), "E", COLOR_END);

                // draw waypoint for currently selected track position in the slider
                final int currentTrackPositionIndex = slPosition.valueProperty().intValue();
                final GeoPosition currentGeoPosition = getGeoPositionForExerciseSample(currentTrackPositionIndex);
                if (currentGeoPosition != null) {
                    drawWaypoint(g, currentGeoPosition, null, COLOR_POSITION);
                }

                g.dispose();
            }
        };
        mapKit.getMainMap().setOverlayPainter(lineOverlay);
    }

    /**
     * Draws a red line which connects all GeoPosition of the track.
     *
     * @param g the Graphics2D context
     * @param geoPositions list of GeoPosition objects of this track
     */
    private void drawTrackLine(Graphics2D g, List<GeoPosition> geoPositions) {
        g.setColor(COLOR_TRACK);
        g.setStroke(new BasicStroke(2));

        int lastX = -1;
        int lastY = -1;

        for (GeoPosition geoPosition : geoPositions) {
            java.awt.geom.Point2D pt = convertGeoPosToPixelPos(geoPosition);
            if (lastX != -1 && lastY != -1) {
                g.drawLine(lastX, lastY, round(pt.getX()), round(pt.getY()));
            }
            lastX = round(pt.getX());
            lastY = round(pt.getY());
        }
    }

    private int round(double value) {
        return (int) Math.round(value);
    }

    /**
     * Draws a waypoint circle and a description text at the specified GeoPosition.
     *
     * @param g the Graphics2D context
     * @param geoPosition position of the waypoint
     * @param text the description text (optional), will be drawn right to the circle
     * @param color the color of the circle
     */
    private void drawWaypoint(Graphics2D g, GeoPosition geoPosition, String text, Color color) {
        final int RADIUS = 5;

        java.awt.geom.Point2D pt = convertGeoPosToPixelPos(geoPosition);

        // draw an outer gray circle, so it's better visible on backgrounds with same color
        g.setColor(Color.GRAY);
        g.setStroke(new BasicStroke(3));
        g.draw(new Ellipse2D.Double(pt.getX() - (RADIUS + 1), pt.getY() - (RADIUS + 1), (RADIUS * 2) + 2,
                (RADIUS * 2) + 2));

        g.setColor(color);
        g.setStroke(new BasicStroke(3));
        g.draw(new Ellipse2D.Double(pt.getX() - RADIUS, pt.getY() - RADIUS, RADIUS * 2, RADIUS * 2));

        // draw the text right from the circle with a gray shadow
        if (text != null) {
            int textPosX = round(pt.getX() + RADIUS * 2.2);
            int textPosY = round(pt.getY() + 3);

            g.setFont(new Font("Dialog.bold", Font.BOLD, 12));

            g.setColor(Color.DARK_GRAY);
            g.drawString(text, textPosX + 1, textPosY + 1);
            g.setColor(color);
            g.drawString(text, textPosX, textPosY);
        }
    }

    private List<GeoPosition> createSampleGeoPositionList(EVExercise exercise) {
        ArrayList<GeoPosition> geoPositions = new ArrayList<>();

        for (ExerciseSample exerciseSample : exercise.getSampleList()) {
            Position pos = exerciseSample.getPosition();
            if (pos != null) {
                geoPositions.add(new GeoPosition(pos.getLatitude(), pos.getLongitude()));
            }
        }
        return geoPositions;
    }

    private List<GeoPosition> createLapGeoPositionList(EVExercise exercise) {
        ArrayList<GeoPosition> geoPositions = new ArrayList<>();

        // ignore last lap split position, it's the exercise end position
        for (int i = 0; i < exercise.getLapList().length - 1; i++) {
            Lap lap = exercise.getLapList()[i];
            Position pos = lap.getPositionSplit();
            if (pos != null) {
                geoPositions.add(new GeoPosition(pos.getLatitude(), pos.getLongitude()));
            }
        }
        return geoPositions;
    }

    private java.awt.geom.Point2D convertGeoPosToPixelPos(GeoPosition geoPosition) {
        return mapKit.getMainMap().getTileFactory().geoToPixel(geoPosition, mapKit.getMainMap().getZoom());
    }

    private GeoPosition convertPixelPosToGeoPos(java.awt.geom.Point2D point) {
        return mapKit.getMainMap().getTileFactory().pixelToGeo(point, mapKit.getMainMap().getZoom());
    }

    private GeoPosition getGeoPositionForExerciseSample(final int sampleIndex) {
        final EVExercise exercise = getDocument().getExercise();
        final Position samplePosition = exercise.getSampleList()[sampleIndex].getPosition();

        // some exercise samples don't have position data
        if (samplePosition != null) {
            return new GeoPosition(samplePosition.getLatitude(), samplePosition.getLongitude());
        }
        return null;
    }

    /**
     * This method must be called on every mouse movement. It searches for an exercise samples
     * nearby the mouse position. If a sample was found, then a tooltip with all the sample
     * details will be shown.
     *
     * @param e the MouseEvent
     */
    private void lookupNearbySampleAndCreateToolTip(MouseEvent e) {

        // get mouse position in the map component (translation needed)
        // => the offset of 1 pixel is needed for proper centered detection of nearby trackpoints
        Rectangle rect = mapKit.getMainMap().getViewportBounds();
        Point mousePos = e.getPoint();
        mousePos.translate(rect.x - 1, rect.y - 1);
        GeoPosition mouseGeoPos = convertPixelPosToGeoPos(mousePos);

        // compute the latitude and longitude distance buffer for searching a nearby sample
        Point bufferPos = new Point(mousePos.x + TRACKPOINT_TOOLTIP_DISTANCE_BUFFER, mousePos.y
                - TRACKPOINT_TOOLTIP_DISTANCE_BUFFER);
        GeoPosition bufferGeoPos = convertPixelPosToGeoPos(bufferPos);

        double latitudeBuffer = Math.abs(bufferGeoPos.getLatitude() - mouseGeoPos.getLatitude());
        double longitudeBuffer = Math.abs(bufferGeoPos.getLongitude() - mouseGeoPos.getLongitude());

        // lookup a nearby sample and show tooltip text when found (or delete tooltip if not found)
        String toolTipText = null;

        int nearBySampleIndex = getSampleIndexNearbyGeoPos(mouseGeoPos, latitudeBuffer, longitudeBuffer);
        if (nearBySampleIndex >= 0) {
            toolTipText = createToolTipText(nearBySampleIndex);
        }

        // hide or display tooltip next to the cursor position (must be executed on the JavaFX UI thread)
        final String finalTooltipText = toolTipText;
        Platform.runLater(() -> {
            if (finalTooltipText == null) {
                spMapViewerTooltip.hide();
            } else {
                Point2D tooltipPos = spMapViewer.localToScene(e.getX() + 8d, e.getY() + 8d);
                tooltipPos = tooltipPos.add(getMapViewerScreenPosition());
                spMapViewerTooltip.setText(finalTooltipText);
                spMapViewerTooltip.show(spMapViewer, tooltipPos.getX(), tooltipPos.getY());
            }
        });
    }

    private Point2D getMapViewerScreenPosition() {
        final Scene scene = spMapViewer.getScene();
        final Window window = scene.getWindow();
        return new Point2D(scene.getX() + window.getX(), scene.getY() + window.getY());
    }

    /**
     * Searches for the exercise sample with the position nearby the specified position.
     *
     * @param geoPos the position to search for a nearby exercise sample
     * @param latitudeBuffer longitude distance buffer, the exercise sample must be located closer
     * @param longitudeBuffer longitude distance buffer, the exercise sample must be located closer
     * @return the index of the found exercise sample or -1 when no sample found
     */
    private int getSampleIndexNearbyGeoPos(GeoPosition geoPos, double latitudeBuffer, double longitudeBuffer) {
        EVExercise exercise = getDocument().getExercise();

        for (int i = 0; i < exercise.getSampleList().length; i++) {
            ExerciseSample sample = exercise.getSampleList()[i];
            Position samplePos = sample.getPosition();

            if (samplePos != null && Math.abs(samplePos.getLatitude() - geoPos.getLatitude()) < latitudeBuffer
                    && Math.abs(samplePos.getLongitude() - geoPos.getLongitude()) < longitudeBuffer) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Creates the tool tip text for the specified exercise sample to be shown on the map.
     *
     * @param sampleIndex index of the exercise sample
     * @return text
     */
    private String createToolTipText(int sampleIndex) {

        EVExercise exercise = getDocument().getExercise();
        ExerciseSample sample = exercise.getSampleList()[sampleIndex];
        FormatUtils formatUtils = getContext().getFormatUtils();

        StringBuilder sb = new StringBuilder();
        appendToolTipLine(sb, "pv.track.tooltip.trackpoint", String.valueOf(sampleIndex + 1));
        appendToolTipLine(sb, "pv.track.tooltip.time",
                formatUtils.seconds2TimeString((int) (sample.getTimestamp() / 1000)));
        appendToolTipLine(sb, "pv.track.tooltip.distance",
                formatUtils.distanceToString(sample.getDistance() / 1000f, 3));
        if (exercise.getRecordingMode().isAltitude()) {
            appendToolTipLine(sb, "pv.track.tooltip.altitude", //
                    formatUtils.heightToString(sample.getAltitude()));
        }
        appendToolTipLine(sb, "pv.track.tooltip.heartrate", //
                formatUtils.heartRateToString(sample.getHeartRate()));
        if (exercise.getRecordingMode().isSpeed()) {
            appendToolTipLine(sb, "pv.track.tooltip.speed", //
                    formatUtils.speedToString(sample.getSpeed(), 2));
        }
        if (exercise.getRecordingMode().isTemperature()) {
            appendToolTipLine(sb, "pv.track.tooltip.temperature", //
                    formatUtils.temperatureToString(sample.getTemperature()));
        }
        return sb.toString();
    }

    private void appendToolTipLine(StringBuilder sb, String resourceKey, String value) {
        sb.append(getContext().getResources().getString(resourceKey));
        sb.append(": ").append(value).append("\n");
    }
}
