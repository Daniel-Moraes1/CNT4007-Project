package src;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Log {
    private final int id;
    private final String path;

    public Log(int peerID) {
        this.id = peerID;
        this.path = "log_peer_" + peerID + ".log";
    }

    private String timeNow() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date = new Date();
        return dateFormat.format(date);
    }

    private void makeLog(String message) throws IOException {
        try (FileWriter writer = new FileWriter(path, true)) {
            writer.write("[" + timeNow() + "]: " + message + "\n");
        } catch (IOException e) {
            throw new IOException("Failed to write to log!");
        }
    }

    public void logTCPConnection(int peerID1, int peerID2) throws IOException {
        makeLog("Peer " + peerID1 + " makes a connection to Peer " + peerID2 + ".");
    }

    public void logConnectedFrom(int peerID1, int peerID2) throws IOException {
        makeLog("Peer " + peerID1 + " is connected from Peer " + peerID2 + ".");
    }

    public void logPreferredNeighbors(int peerID, String preferredNeighborList) throws IOException {
        makeLog("Peer " + peerID + " has the preferred neighbors " + preferredNeighborList + ".");
    }

    public void logOptimisticallyUnchokedNeighbor(int peerID, int optimisticallyUnchokedNeighborID) throws IOException {
        makeLog("Peer " + peerID + " has the optimistically unchoked neighbor " + optimisticallyUnchokedNeighborID + ".");
    }

    public void logUnchoked(int peerID1, int peerID2) throws IOException {
        makeLog("Peer " + peerID1 + " is unchoked by " + peerID2 + ".");
    }

    public void logChoked(int peerID1, int peerID2) throws IOException {
        makeLog("Peer " + peerID1 + " is choked by " + peerID2 + ".");
    }

    public void logReceivedHave(int peerID1, int peerID2, int pieceIndex) throws IOException {
        makeLog("Peer " + peerID1 + " received the 'have' message from " + peerID2 + " for the piece " + pieceIndex + ".");
    }

    public void logReceivedInterested(int peerID1, int peerID2) throws IOException {
        makeLog("Peer " + peerID1 + " received the 'interested' message from " + peerID2 + ".");
    }

    public void logReceivedNotInterested(int peerID1, int peerID2) throws IOException {
        makeLog("Peer " + peerID1 + " received the 'not interested' message from " + peerID2 + ".");
    }

    public void logDownloadedPiece(int peerID1, int peerID2, int pieceIndex, int numberOfPieces) throws IOException {
        makeLog("Peer " + peerID1 + " has downloaded the piece " + pieceIndex + " from " + peerID2 + ". Now the number of pieces it has is " + numberOfPieces + ".");
    }

    public void logCompletionOfDownload(int peerID) throws IOException {
        makeLog("Peer " + peerID + " has downloaded the complete file.");
    }
}
