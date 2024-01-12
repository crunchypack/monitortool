package Monitor.tool;

import org.bson.Document;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;

/**
 * Tool that monitors a Mongo database and counts the amount of documents in the
 * collections
 */
public class MonitorTool {
	private static final Logger logger = LoggerFactory.getLogger(MonitorTool.class);
	private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private static long lastChunksCount = 0;
	private static long lastClonesCount = 0;
	private static long lastFilesCount = 0;
	private static long lastCandidatesCount = 0;
	private static boolean trackingChunks = false;
	private static boolean trackingCandidates = false;
	private static boolean trackingClones = false;
	private static Instant lastCollectionTime = Instant.now();
	private static DefaultCategoryDataset clonesDataset = new DefaultCategoryDataset();
	private static DefaultCategoryDataset candidatesDataset = new DefaultCategoryDataset();
	private static DefaultCategoryDataset chunksDataset = new DefaultCategoryDataset();

	private static JFreeChart clonesChart;
	private static JFreeChart candidatesChart;
	private static JFreeChart chunksChart;

	private static ChartPanel clonesChartPanel;
	private static ChartPanel candidatesChartPanel;
	private static ChartPanel chunksChartPanel;
	private static JFrame frame;

	/**
	 * Main method that sets up database connection and gets the collections. Then
	 * is calls a method every minute.
	 * 
	 */
	public static void main(String[] args) {
		String DEFAULTDB = "localhost:27017";
		String connection = System.getenv("DBHOST") != null ? System.getenv("DBHOST") : DEFAULTDB;

		try {
			logger.info("Attempting to connect to the MongoDB database...");
			var mongoClient = MongoClients.create("mongodb://" + connection);
			MongoDatabase db = mongoClient.getDatabase("cloneDetector");
			MongoCollection<Document> filesCollection = db.getCollection("files");
			MongoCollection<Document> chunksCollection = db.getCollection("chunks");
			MongoCollection<Document> candidatesCollection = db.getCollection("candidates");
			MongoCollection<Document> clonesCollection = db.getCollection("clones");
			MongoCollection<Document> statusUpdatesCollection = db.getCollection("statusUpdates");
			
			FindIterable<Document> documents = clonesCollection.find();
			MongoCursor<Document> iterator = documents.iterator();

			int totalLength = 0;
			int numberOfClones = 0;

			while (iterator.hasNext()) {
				Document document = iterator.next();
				totalLength += calculateCloneLength(document);
				numberOfClones++;
			}

			if (numberOfClones > 0) {
				double averageLength = (double) totalLength / numberOfClones;
				System.out.println("Average Clone Length: " + averageLength);
			} else {
				System.out.println("No clones found in the collection.");
			}
			

//			scheduler.scheduleAtFixedRate(() -> {
//				collectAndPrintStatistics(filesCollection, chunksCollection, candidatesCollection, clonesCollection,
//						statusUpdatesCollection);
//			}, 0, 5, TimeUnit.MINUTES);
		} catch (Exception e) {
			logger.error("Failed to connect to the MongoDB database.", e);
		}
	}
	/**
	 * Method that gets the lenght of a clone in the database
	 * @param document the document from clones collection
	 * @return lenght of clone
	 */

	private static long calculateCloneLength(Document document) {
		List<Document> instances = document.getList("instances", Document.class);
		long startLine = 0;
		long endLine = 0;
		// Looping through each instance and getting startLine and endLine
		for (Document instance : instances) {
			String fileName = instance.getString("fileName");
			
		     startLine = instance.getLong("startLine");
		     endLine = instance.getLong("endLine");
		     System.out.println(fileName + " clone length: " + (endLine - startLine) + " lines");
		     

		}

		return endLine - startLine;
	}

	/**
	 * Method that counts, prints and draws the document counts from the database to
	 * figure out the processing times of the cljDetector.
	 * 
	 * @param filesCollection
	 * @param chunksCollection
	 * @param candidatesCollection
	 * @param clonesCollection
	 * @param statusUpdatesCollection
	 */
	private static void collectAndPrintStatistics(MongoCollection<Document> filesCollection,
			MongoCollection<Document> chunksCollection, MongoCollection<Document> candidatesCollection,
			MongoCollection<Document> clonesCollection, MongoCollection<Document> statusUpdatesCollection) {
		System.out.println("Collecting");

		try {
			String statusUpdateMessage = getStatusUpdateMessage(statusUpdatesCollection);
			System.out.println(statusUpdateMessage);
			long filesCount = filesCollection.countDocuments();
			long chunksCount = chunksCollection.countDocuments();
			long candidatesCount = candidatesCollection.countDocuments();
			long clonesCount = clonesCollection.countDocuments();
			long statusUpdatesCount = statusUpdatesCollection.countDocuments();
			Instant currentTime = Instant.now();
			Duration timeElapsed = Duration.between(lastCollectionTime, currentTime);

			switch (statusUpdateMessage) {
			case "Clearing database...":
				// Reset all tracking flags
				trackingChunks = false;
				trackingCandidates = false;
				trackingClones = false;
				break;
			case "Storing chunks of size20...":
				// Start tracking chunks
				trackingChunks = true;
				trackingCandidates = false;
				trackingClones = false;
				break;

			case "Expanding Candidates...":

				trackingChunks = false;
				trackingCandidates = true;
				trackingClones = true;
				break;
			case "Identifying Clone Candidates...":
				trackingChunks = false;
				trackingCandidates = true;
				trackingClones = false;
				break;
			case "Summary":
				// Stop the scheduler and exit the program
				FindIterable<Document> documents = clonesCollection.find();
				MongoCursor<Document> iterator = documents.iterator();

				int totalLength = 0;
				int numberOfClones = 0;

				while (iterator.hasNext()) {
					Document document = iterator.next();
					totalLength += calculateCloneLength(document);
					numberOfClones++;
				}

				if (numberOfClones > 0) {
					double averageLength = (double) totalLength / numberOfClones;
					System.out.println("Average Clone Length: " + averageLength);
				} else {
					System.out.println("No clones found in the collection.");
				}
				scheduler.shutdown();
				logger.info("Program stopped due to 'Summary' status update.");
				System.exit(0);
			default:
				trackingChunks = false;
				trackingCandidates = false;
				trackingClones = false;
				break;
			}

			// Continue tracking based on the flags
			if (trackingChunks) {
				// Track chunks
				long newChunks = chunksCount - lastChunksCount;
				loggInfo("Stats: files=%d , chunks=%d (+%d in %s), statusUpdates=%d", filesCount, timeElapsed,
						chunksCount, newChunks, statusUpdatesCount);
				chunksDataset.addValue(newChunks, "Chunks", formatTime(currentTime));

			}
			if (trackingCandidates) {
				// Track candidates
				long newCandidates = candidatesCount - lastCandidatesCount;
				loggInfo("Stats: files=%d , candidates=%d (+%d in %s), statusUpdates=%d", filesCount, timeElapsed,
						candidatesCount, newCandidates, statusUpdatesCount);
				candidatesDataset.addValue(newCandidates, "Candidates", formatTime(currentTime));
			}
			if (trackingClones) {
				// Track clones
				long newClones = clonesCount - lastClonesCount;
				clonesDataset.addValue(newClones, "Clones", formatTime(currentTime));
				loggInfo("Stats: files=%d , clones=%d (+%d in %s), statusUpdates=%d", filesCount, timeElapsed,
						clonesCount, newClones, statusUpdatesCount);
			}

			lastFilesCount = filesCount;
			lastChunksCount = chunksCount;
			lastCandidatesCount = candidatesCount;
			lastClonesCount = clonesCount;
			lastCollectionTime = currentTime;

			drawGraph();

		} catch (Exception e) {
			logger.error("Failed to collect and print. ", e);
			System.out.println(e);
		}
		System.out.println("Collecting finished....");
	}

	/**
	 * Helper method that prints out relevant information the console
	 * 
	 * @param stats
	 * @param filesCount
	 * @param timeElapsed
	 * @param count
	 * @param newCount
	 * @param status
	 */
	private static void loggInfo(String stats, long filesCount, Duration timeElapsed, long count, long newCount,
			long status) {
		logger.info(String.format(stats, filesCount, count, newCount, formatDuration(timeElapsed), status));
	}

	/**
	 * Helper method that formats time elapsed for readability
	 * 
	 * @param duration the object that needs to be formatted
	 * @return a string in the format mm:ss
	 */
	private static String formatDuration(Duration duration) {
		long seconds = duration.getSeconds();
		long absSeconds = Math.abs(seconds);
		String positive = String.format("%d:%02d", absSeconds / 60, absSeconds % 60);
		return seconds < 0 ? "-" + positive : positive;
	}

	/**
	 * Method that calls methods to draw graphs if not running in headless mode,
	 * else it exports data to CSV
	 */
	private static void drawGraph() {

		if (GraphicsEnvironment.isHeadless()) {
			exportDatasetsToCSV();
		} else {
			// Create or update the chart for clones
			createOrUpdateChart(clonesChart, clonesChartPanel, "Clones", clonesDataset);

			// Create or update the chart for candidates
			createOrUpdateChart(candidatesChart, candidatesChartPanel, "Candidates", candidatesDataset);

			// Create or update the chart for chunks
			createOrUpdateChart(chunksChart, chunksChartPanel, "Chunks", chunksDataset);
		}

	}

	/**
	 * Method that draws graphs with datasets, charts and panels given.
	 * 
	 * @param chart
	 * @param chartPanel
	 * @param title
	 * @param dataset
	 */
	private static void createOrUpdateChart(JFreeChart chart, ChartPanel chartPanel, String title,
			CategoryDataset dataset) {
		if (chart == null || chartPanel == null) {
			// If components are not initialized, create them
			chart = ChartFactory.createLineChart(title, "Time", "Count", dataset, PlotOrientation.VERTICAL, true, true,
					false);

			chartPanel = new ChartPanel(chart);
			chartPanel.setPreferredSize(new Dimension(800, 600));

			if (title.equals("Clones")) {
				clonesChart = chart;
				clonesChartPanel = chartPanel;
			} else if (title.equals("Candidates")) {
				candidatesChart = chart;
				candidatesChartPanel = chartPanel;
			} else if (title.equals("Chunks")) {
				chunksChart = chart;
				chunksChartPanel = chartPanel;
			}

			if (frame == null) {
				frame = new JFrame("Statistics Graph");
				frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
				frame.setLayout(new GridLayout(3, 1)); // Adjust the layout to accommodate three charts
				frame.pack();
				frame.setVisible(true);
			}

			// Add the chartPanel to the frame
			frame.getContentPane().add(chartPanel);
			frame.pack();
		} else {
			// Update the existing dataset in the chart
			chart.getCategoryPlot().setDataset(dataset);

			// Trigger a repaint of the chartPanel
			chartPanel.repaint();
		}
	}

	/**
	 * Method that formats time for the graph
	 * 
	 * @param time
	 * @return formatted time in LocalDateTime HH:mm:ss
	 */
	private static String formatTime(Instant time) {
		LocalDateTime localDateTime = LocalDateTime.ofInstant(time, ZoneId.systemDefault());
		return localDateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
	}

	/**
	 * Method that gets latest status update from the database
	 * 
	 * @param statusUpdatesCollection
	 * @return string of the message
	 */
	private static String getStatusUpdateMessage(MongoCollection<Document> statusUpdatesCollection) {
		// Retrieve the latest status update message
		Document latestStatusUpdate = statusUpdatesCollection.find().sort(Sorts.descending("timestamp")).first();
		return latestStatusUpdate.getString("message");
	}

	/**
	 * Method to export the datasets to CSV files.
	 */
	private static void exportDatasetsToCSV() {
		// /usr/src/app/csvs/
		if (trackingChunks)
			exportDatasetToCSV(chunksDataset, "/usr/src/app/csvs/chunks_dataset.csv");
		if (trackingCandidates)
			exportDatasetToCSV(candidatesDataset, "/usr/src/app/csvs/candidates_dataset.csv");
		if (trackingClones)
			exportDatasetToCSV(clonesDataset, "/usr/src/app/csvs/clones_dataset.csv");

	}

	/**
	 * Method to export a dataset to a CSV file.
	 *
	 * @param dataset  The dataset to export
	 * @param filename The name of the CSV file
	 */
	private static void exportDatasetToCSV(CategoryDataset dataset, String filename) {
		try (FileWriter writer = new FileWriter(filename, true)) {

			// Write data
			for (int i = 0; i < dataset.getRowCount(); i++) {
				double count = (double) dataset.getValue(i, 0);
				String name = dataset.getRowKey(i).toString();
				for (int c = 0; c < dataset.getColumnCount(); c++) {
					String time = dataset.getColumnKey(c).toString();
					double newChunks = (double) dataset.getValue(i, c);

					writer.append(time + "," + name + ",+" + newChunks + "\n");
				}

			}

			System.out.println("Dataset exported to: " + filename);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}