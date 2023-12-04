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

    public String timeNow() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date = new Date();
        return dateFormat.format(date);
    }

    private synchronized void makeLog(String message) throws IOException {
            try (FileWriter writer = new FileWriter(path, true)) {
                writer.write("[" + timeNow() + "]: " + message + "\n");
            } catch (IOException e) {
                System.out.println(message);
                e.printStackTrace();
                throw new IOException("Failed to write to log!");
            }
    }

    public void logConnectedTo(int peerID1, int peerID2) throws IOException {
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
    public void logSetupVariables(int peerID, int welcomePort, int numPeers, boolean hasFile, int numPrefNeighbors,
                                  int unChokingInterval, int optimisticUnchokeInterval, String fileName, long fileSize, long pieceSize) throws IOException {
        makeLog("Peer " + peerID + " has started configuration.");
        makeLog("Peer " + peerID + " has read PeerInfo file and is listening on port " + welcomePort);
        makeLog("Peer " + peerID + " has " + numPeers + " peers and " + numPrefNeighbors + " preferred peers");
        makeLog("Peer " + peerID + " has read Common.cfg and set unchoking interval to " + unChokingInterval + " and optimistic unchoking interval to " + optimisticUnchokeInterval);
        makeLog("Peer " + peerID + " is part of the network distributing file " + fileName + " of size " + fileSize + " at a piece size of " + pieceSize);
        if (hasFile) {
            makeLog("Peer " + peerID + " has the file at startup");
        }
        else {
            makeLog("Peer " + peerID + " does not have the file at startup");

        }
    }
    public void logShutdown() throws IOException {
        makeLog("All peers have finished downloading the file. Shutting down all threads and sockets and terminating");
    }

    public void receivedHandshake(int id, int neighborId, String message) throws IOException {
        makeLog("Peer " + id + " has received handshake message " + message + " from peer neighborId");
        makeLog("Peer " + id + " is starting communication with " + neighborId);
    }

    public void sendBitfield(int id, int neighborId, boolean send) throws IOException {
        if (send) {
            makeLog("Peer " + id + " has sent bitfield to neighbor " + neighborId);
        }
        else {
            makeLog("Peer " + id + " has no pieces and is not sending bitfield message to neighbor " + neighborId);
        }
    }

    public void logSendInterested(int id, int neighborId) throws IOException {
        makeLog("Peer " + id + " is sending interested message to " + neighborId);
    }

    public void logSendNotInterested(int id, int neighborId) throws IOException {
        makeLog("Peer " + id + " is sending not interested message to " + neighborId);
    }

    public void logReceivedPieceCounts(int id, String message) throws IOException {
        makeLog("Peer " + id + " has sent the following amount of files to the following interested peers in the last interval: " + message);
    }

    public void logSendRequest(int id, int neighbor, int pieceIndex)  throws IOException {
        makeLog("Peer " + id + " has sent a REQUEST for piece " + pieceIndex + " to neighbor " + neighbor);
    }

    public void logSendHave(int id, int neighbor, int pieceIndex)  throws IOException {
        makeLog("Peer " + id + " is sending HAVE for piece " + pieceIndex + " to neighbor " + neighbor);
    }

    public void logSendPiece(int id, int neighbor, int pieceIndex)  throws IOException {
        makeLog("Peer " + id + " is sending PIECE with index " + pieceIndex + " to neighbor " + neighbor);
    }
}
