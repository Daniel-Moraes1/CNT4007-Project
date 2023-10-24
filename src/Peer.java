package src;

import java.io.*;
import java.util.HashSet;
import java.util.Random;
import java.util.Vector;
import java.lang.System;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.*;


public class Peer {

    private volatile int id;
    private volatile BitSet bitfield;
    private volatile BitSet requested;
    private volatile List <Neighbor> neighbors;
    private volatile HashSet <Neighbor> unchokedNeighbors;
    private volatile HashSet <Neighbor> chokedNeighbors;
    private volatile int countFinishedNeighbors;
    private volatile int numConnections;
    private int maxConnections;
    private volatile int unchokeInterval;
    private int optimisticUnchokeInterval;
    private String fileName;
    private long fileSize;
    private long pieceSize;
    private int totalPieces;
    private volatile int numPieces;
    private volatile ServerSocket welcomeSocket;
    private volatile boolean finished;
    private volatile boolean listening;
    private volatile P2PFile p2pFile;
    private volatile Thread welcomeThread;
    private volatile long lastUnchoke;
    private volatile long lastOptimisticUnchoke;
    public volatile Neighbor optimisticUnchokedNeighbor;

    public class Neighbor {
        public volatile int id;
        public volatile String address;
        public volatile int welcomePort;
        public volatile int packetCount;
        public volatile BitSet bitfield;
        public volatile boolean finished;
        public volatile Socket connection;
        public volatile boolean  interestedInPeer; // Is neighbor interested in Peer's pieces
        public volatile boolean  interestedInNeighbor; // Is Peer interested in neighbor's pieces
        public volatile boolean chokedByNeighbor; // Is Peer choked by neighbor
        public volatile HashSet<Integer> piecesForPeer; // Track pieces neighbor has that peer does not have
        public volatile boolean waitingForPiece;
        public volatile int piecesInInterval;
        public volatile Thread requestThread;
        public volatile Thread initiatorThread;


        public Neighbor(int id_, String address_, int welcomePort_) throws IOException, ClassNotFoundException, InterruptedException {
            this.id = id_;
            this.address = address_;
            this.welcomePort = welcomePort_;
            this.bitfield = new BitSet(totalPieces);
            this.finished = false;
            this.interestedInPeer = false;
            this.interestedInNeighbor = false;
            this.chokedByNeighbor = true;
            this.packetCount = 0;
            this.piecesForPeer = new HashSet<Integer>();
            this.waitingForPiece = false;
            this.piecesInInterval = 0;
        }

        public Neighbor(int id, Socket connection_) throws IOException, ClassNotFoundException {
            this.id = id;
            this.bitfield = new BitSet(totalPieces);
            this.finished = false;
            this.connection = connection_;
            this.interestedInPeer = false;
            this.interestedInNeighbor = false;
            this.chokedByNeighbor = true;
            this.packetCount = 0;
            this.piecesForPeer = new HashSet<Integer>();
            this.waitingForPiece = false;
            this.piecesInInterval = 0;
        }
    }

    public Peer(int id_, int maxConnections_, int unchokingInterval_,
                int optimisticUnchokingInterval_, String fileName_,
                long fileSize_, long pieceSize_, int welcomePort_, boolean hasFile_, Vector<NeighborInfo> neighborInfo)
                throws IOException, InterruptedException, ClassNotFoundException {
        this.id = id_;
        this.maxConnections = maxConnections_;
        this.unchokeInterval = unchokingInterval_;
        this.optimisticUnchokeInterval = optimisticUnchokingInterval_;
        this.fileName = fileName_;
        this.fileSize = fileSize_;
        this.pieceSize = pieceSize_;
        this.totalPieces = (int)(fileSize_ / pieceSize);
        if (fileSize_ % pieceSize > 0) {
            this.totalPieces++;
        }
        this.welcomeSocket = new ServerSocket(welcomePort_);
        this.listening = true;
        this.bitfield = new BitSet(totalPieces);
        this.requested = new BitSet(totalPieces);
        // If peer has the file set bits for all pieces to true
        if (hasFile_) {
            this.bitfield.set(0, bitfield.size(), true);
            this.numPieces = totalPieces;
            this.finished = true;
        }
        this.p2pFile = new P2PFile(fileName_, fileSize, pieceSize);

        for (int i=0; i<neighborInfo.size(); i++) {
            Neighbor n = new Neighbor(neighborInfo.get(i).id, neighborInfo.get(i).name, neighborInfo.get(i).port);
            neighbors.add(n);
            n.connection = fetchPort(n);
        }

        this.welcomeThread = new Thread(() -> {
            try {
                this.listenForNewNeighbor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        welcomeThread.run();
        welcomeThread.join();
    }

    private Socket fetchPort(Neighbor n) throws IOException, ClassNotFoundException {
        Socket tempSocket = new Socket(n.address, n.welcomePort);
        ObjectInputStream in = new ObjectInputStream(tempSocket.getInputStream());
        int portNumber = in.readInt();
        tempSocket.close();
        Socket newSocket = new Socket(n.address, portNumber);
        if (numConnections < maxConnections) {
            this.unchokedNeighbors.add(n);
            numConnections++;
        }
        else {
            this.unchokedNeighbors.add(n);
        }
        return new Socket(n.address,  portNumber);
    }

    private void listenForNewNeighbor() throws Exception {
        try {
            while(listening) {
                Socket connection = welcomeSocket.accept(); // welcome socket connection

                ServerSocket s = new ServerSocket(0);
                int port = s.getLocalPort();
                ObjectOutputStream out;
                out = new ObjectOutputStream(connection.getOutputStream());
                out.writeInt(port); // write port number through welcome socket connection

                // Migrate neighbor to new connection off of the welcome socket
                Thread connectionThread = new Thread(() -> {
                    try {
                        this.makeNewConnection(s);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                });
                connectionThread.start();
                connectionThread.join(10000); // Allow 10 seconds for neighbor to connect


            }
        }
        catch(Exception e) {
            throw new Exception("Welcome socket failed");
        }
    }

    public void makeNewConnection (ServerSocket s) throws Exception {
        s.setSoTimeout(10000);
        Socket connection;
        try {
            connection = s.accept();
        }
        catch(SocketTimeoutException e) {
            throw new SocketTimeoutException("Client socket failed to connect to new socket");
        }  catch (IOException e) {
            throw new IOException("IO Exception occurred while waiting for client to connect.");
        }

        connection.setSoTimeout(0);
        int id = handShake(connection);
        if (id == -1) {
            throw new Exception("Received invalid neighbor ID from handshake");
        }

        Neighbor n = new Neighbor(id, connection);
        neighbors.add(n);
        if (numConnections < maxConnections) {
            this.unchokedNeighbors.add(n);
            numConnections++;
        }
        else {
            this.chokedNeighbors.add(n);
        }
        createNeighborThreads(n);
    }

    public void createNeighborThreads(Neighbor n) throws InterruptedException {
        n.requestThread = new Thread(() -> {
            try {
                this.responder(n);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        n.requestThread.start();
        n.requestThread.join();
        n.initiatorThread = new Thread(() -> {
            try {
                this.initiator(n);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        n.initiatorThread.start();
        n.initiatorThread.join();
    }

    public int handShake(Socket s) throws Exception {
        // Project spec does not specify that a handshake ACK should come through
        // Leaving it out for now.
        ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
        ObjectInputStream in = new ObjectInputStream(s.getInputStream());
        out.writeObject("P2PFILESHARINGPROJ" + "          " + Integer.toString(this.id));
        String response = (String)in.readObject();
        int neighborId = Integer.valueOf(response.substring(27));
        if (response.substring(0,27) != ("P2PFILESHARINGPROJ" + "          ")) {
            // Incorrect response from neighbor, need to handle
            neighborId = -1;
            throw new Exception("Received wrong connection message from client: " + response);
        }
        return neighborId;
    }

    // Thread for reading from neighbor connection
    public void responder(Neighbor neighbor) throws Exception {
        // For each message, get message type
        InputStream in = neighbor.connection.getInputStream();
        while(listening) {
            byte[] lengthBytes = in.readNBytes(4); // Wait for message to come in
            int messageLength = fourBytesToInt(lengthBytes);

            int type = in.read();
            switch(type) {
                // Choke
                // Stop receiving messages from neighbor
                case 0:
                    neighbor.chokedByNeighbor = true;
                    break;

                // Unchoke
                // Begin receiving messages from neighbor
                case 1:
                    neighbor.chokedByNeighbor = false;
                    break;

                //Interested
                case 2:
                    neighbor.interestedInPeer = true;
                    break;

                // Not Interested
                case 3:
                    neighbor.interestedInPeer = false;
                    if (!neighbor.finished) {
                        boolean fin = true;
                        for (int i=0; i<totalPieces; i++) {
                            if (!neighbor.bitfield.get(i)) {
                                fin = false;
                            }
                            neighbor.bitfield.set(i, neighbor.bitfield.get(i) || this.bitfield.get(i));
                        }
                        neighbor.finished = fin;
                        countFinishedNeighbors++;
                        checkDone();
                    }
                    break;

                // Have
                case 4:
                    byte[] byteIndex = in.readNBytes(4);

                    int index = fourBytesToInt(byteIndex);
                    if (!this.bitfield.get(index)) {
                        neighbor.piecesForPeer.add(index);
                        sendMessage(MessageType.INTERESTED, neighbor, null);
                    }
                    break;

                // Bitfield
                case 5:
                    byte[] bitfieldBytes = in.readNBytes(messageLength-1);
                    neighbor.bitfield = BitSet.valueOf(bitfieldBytes);
                    for (int i=0; i<neighbor.bitfield.size(); i++) {
                        if (neighbor.bitfield.get(i) && !this.bitfield.get(i)) {
                            neighbor.piecesForPeer.add(i);
                        }
                    }
                    checkInterestInNeighbor(neighbor);

                    if (neighbor.interestedInNeighbor) {
                        sendMessage(MessageType.INTERESTED, neighbor, null); // Send interested in neighbor
                    }
                    else {
                        sendMessage(MessageType.NOT_INTERESTED, neighbor, null); // Send not interested in neighbor
                    }
                    break;

                // Request
                case 6:
                    byte[] requestIndexBytes = in.readNBytes(4);
                    int requestedIndex = fourBytesToInt(requestIndexBytes);
                    if (unchokedNeighbors.contains(neighbor)) {
                        sendMessage(MessageType.PIECE, neighbor, p2pFile.getPiece(requestedIndex));
                        neighbor.piecesInInterval++;
                    }
                    else {
                        // We can send an empty piece for the request index if the neighbor has been choked
                        // This will let then know to request the piece from another neighbor
                        sendMessage(MessageType.PIECE, neighbor, requestIndexBytes);
                        neighbor.bitfield.set(requestedIndex, true);
                    }
                    break;

                // Piece
                case 7:
                    // The first four bytes of a piece payload is the index
                    byte[] pieceIndexBytes = in.readNBytes(4);
                    int pieceIndex = fourBytesToInt(pieceIndexBytes);
                    if (messageLength == 5) {
                        // We were choked by the neighbor and the piece was not sent over
                        requested.set(pieceIndex, false);
                    }
                    else {
                        byte[] pieceData = in.readNBytes(messageLength-1-4);
                        p2pFile.writePiece(pieceIndex, pieceData);
                        if (!this.bitfield.get(pieceIndex)) {
                            this.bitfield.set(pieceIndex, true);
                            numPieces++;
                            if (numPieces == totalPieces) {
                                this.finished = true;
                            }
                        }

                        // When we get a piece, inform all neighbors that we have that piece. Re-evaluate interest
                        for (int i=0; i<neighbors.size(); i++) {
                            sendMessage(MessageType.HAVE, neighbor, pieceIndexBytes);

                            // Upon receiving a new packet, remove packet index for set of packets that neighbors have and peer does not
                            // If set of missing packets that a neighbor has becomes 0, send NOT_INTERESTED
                            if (neighbors.get(i).piecesForPeer.contains(pieceIndex)) {
                                neighbors.get(i).piecesForPeer.remove(pieceIndex);
                                if (!checkInterestInNeighbor(neighbors.get(i))) {
                                    sendMessage(MessageType.NOT_INTERESTED, neighbor, null);
                                }
                            }
                        }
                        neighbor.waitingForPiece = false;
                    }

                    break;
            }
        }
    }

    // Thread for starting message sends to neighbors (piece requests, chokes/unchokes)
    public void initiator(Neighbor neighbor) throws IOException {
        OutputStream out = neighbor.connection.getOutputStream();
        if (!neighbor.chokedByNeighbor) {
            if (neighbor.interestedInNeighbor ) {
                if (!neighbor.waitingForPiece) {
                    //If we are not choked by the neighbor, neighbor has pieces we do not,
                    // and we do not have an outstanding piece request to neighbor, find a piece to request
                    int random = new Random().nextInt() % neighbor.piecesForPeer.size();
                    int count = 0;
                    int pieceNumber = 0;
                    for (int iterator : neighbor.piecesForPeer) {
                        if (count == random) {
                            pieceNumber = iterator;
                            break;
                        }
                        count++;
                    }
                    byte[] pieceNumberBytes = intToFourBytes(pieceNumber);
                    sendMessage(MessageType.REQUEST, neighbor, pieceNumberBytes);
                    neighbor.waitingForPiece = true;
                }
            }
        }
    }

    // Locks / Mutexes
    public void timer() throws IOException {
        while(true) {
            if (System.nanoTime() >= this.lastUnchoke + this.unchokeInterval) {
                unchoke();
                this.lastUnchoke = System.nanoTime();

            }
            if (System.nanoTime() >= this.lastOptimisticUnchoke + this.unchokeInterval) {
                optimisticUnchoke();
                this.lastOptimisticUnchoke = System.nanoTime();
            }
        }
    }

    private void unchoke() throws IOException {
        // Find peers with greatest download rates
        // We can just sort for now, but not best TC
        Vector<Neighbor> interestedInPeer = new Vector<Neighbor>();
        for (int i=0; i<neighbors.size(); i++) {
            if (neighbors.get(i).interestedInPeer) {
                interestedInPeer.add(neighbors.get(i));
            }
        }
        Collections.sort(interestedInPeer, new SortByDownloadRate());
        Vector<Neighbor> toUnchoke = new Vector<Neighbor>();
        for (int i=0 ; i<interestedInPeer.size()-1; i++) {
            if (toUnchoke.size() == maxConnections) {
                break;
            }
            if (interestedInPeer.get(i).piecesInInterval > interestedInPeer.get(i+1).piecesInInterval) {
                toUnchoke.add(interestedInPeer.get(i));
            }
            else if (interestedInPeer.get(i).piecesInInterval == interestedInPeer.get(i+1).piecesInInterval) {
                Vector<Neighbor> tie = new Vector<Neighbor>();
                while (i < interestedInPeer.size()-1 && interestedInPeer.get(i).piecesInInterval == interestedInPeer.get(i+1).piecesInInterval) {
                    tie.add(neighbors.get(i));
                    i++;
                }
                tie.add(neighbors.get(i));

                if (tie.size() <= maxConnections - toUnchoke.size()) {
                    for (int j=0; j<tie.size(); j++) {
                        toUnchoke.add(tie.get(j));
                    }
                }
                else {
                    // Randomly select preferred neighbors among tied peers
                    Random random = new Random();
                    while (toUnchoke.size() < maxConnections) {
                        int index = new Random().nextInt() % tie.size();
                        toUnchoke.add(tie.get(index));
                        tie.remove(index);
                    }
                }
            }
        }

        // If an unchoked neighbor is not reselected to be unchoked
        for (Neighbor n : unchokedNeighbors) {
            if (!toUnchoke.contains(n) && this.optimisticUnchokedNeighbor != n) {
                this.unchokedNeighbors.remove(n);
                this.chokedNeighbors.add(n);
                sendMessage(MessageType.CHOKE, n, null);
            }
        }

        for (int i=0; i<toUnchoke.size(); i++) {
            if (chokedNeighbors.contains(neighbors.get(i))) {
                chokedNeighbors.remove(neighbors.get(i));
                unchokedNeighbors.add(neighbors.get(i));
                sendMessage(MessageType.UNCHOKE, neighbors.get(i), null);
            }
            if (neighbors.get(i) == this.optimisticUnchokedNeighbor) {
                // TODO should we automatically reassign an optimistic unchoked neighbor if we promote one to preferred?
                this.optimisticUnchokedNeighbor = null;
            }
        }

        // Reset pieces downloaded in interval for all neighbors
        for (int i=0; i<neighbors.size(); i++) {
            neighbors.get(i).piecesInInterval = 0;
        }

    }
    private void optimisticUnchoke() {
        Vector<Neighbor> interested = new Vector<Neighbor>();
        for (Neighbor n : chokedNeighbors) {
            if (n.interestedInPeer) {
                interested.add(n);
            }
        }
        if (interested.size() != 0) {
            int random = new Random().nextInt()%interested.size();
            if (optimisticUnchokedNeighbor != null) {
                unchokedNeighbors.remove(optimisticUnchokedNeighbor);
                chokedNeighbors.add(optimisticUnchokedNeighbor);
            }
            optimisticUnchokedNeighbor = interested.get(random);
            unchokedNeighbors.add(interested.get(random));
            chokedNeighbors.remove(interested.get(random));
        }
        else if (optimisticUnchokedNeighbor != null) {
            // not sure if we need to log if same optimistically unchoked neighbor is reselected
            optimisticUnchokedNeighbor = optimisticUnchokedNeighbor;
        }
    }

    private void sendMessage(MessageType messageType, Neighbor n, byte[] message) throws IOException {
        OutputStream out = n.connection.getOutputStream();
        int messageLength = message.length + 1;
        byte[] messageLengthBytes = intToFourBytes(messageLength);
        int type = -1;
        switch(messageType) {
            case CHOKE:
                type = 0;
                break;
            case UNCHOKE:
                type = 1;
                break;
            case INTERESTED:
                type = 2;
                break;
            case NOT_INTERESTED:
                type = 3;
                break;
            case HAVE:
                type = 4;
                break;
            case BITFIELD:
                type = 5;
                break;
            case REQUEST:
                type = 6;
                break;
            case PIECE:
                type = 7;
                break;
        }
        byte[] typeByte = intToFourBytes(type);
        byte[] fullMessage = new byte[4+1+message.length];
        System.arraycopy(messageLengthBytes, 0, fullMessage, 0, 4);
        System.arraycopy(typeByte, 0, fullMessage, 4, 1);
        System.arraycopy(message, 0, fullMessage, 5, message.length);
        out.write(fullMessage);
    }

    public int fourBytesToInt(byte[] bytes) {
        int sum = 0;
        for (byte b: bytes) {
            sum = (sum << 8) + b;
        }
        return sum;
    }

    public byte[] intToFourBytes(int num) {
        return new byte[]{
                (byte) (num >> 24),
                (byte) (num >> 16),
                (byte) (num >> 8),
                (byte) num
        };
    }


    private boolean checkInterestInNeighbor(Neighbor neighbor) {
        neighbor.interestedInNeighbor = !neighbor.piecesForPeer.isEmpty();
        return neighbor.interestedInNeighbor;
    }

    // If all neighbors and self is done, end all connections with Peer
    private void checkDone() {
        if (this.countFinishedNeighbors == neighbors.size() && this.finished) {
            listening = false;
        }
    }

    // Not sure if this works
    class SortByDownloadRate implements Comparator<Neighbor> {
        public int compare(Neighbor a, Neighbor b) {
            return a.piecesInInterval - b.piecesInInterval;
        }
    }

    public static void main(String[] args) throws Exception {
        int index = -1;
        Scanner scanner;
        Vector<NeighborInfo> peerNeighborInfoFromConfig = new Vector<NeighborInfo>();
        int numPreferredNeighbors = -1, unChokingInterval = -1, idealChokingInterval = -1;
        String fileName = "";
        long fileSize = -1; //will need to be able to store large numbers
        long pieceSize = -1;
        try {
            index = Integer.parseInt(args[0]);
        } catch (Exception e) {
            System.out.println("Invalid input ID");
        }
        try { // Read config info from common.
            File common = new File("./Common.cfg");
            String peerInfoString = Files.readString(common.toPath());
            scanner = new Scanner(peerInfoString);
            String line = scanner.nextLine();
            String[] information = line.split(" ");
            numPreferredNeighbors = Integer.parseInt(information[1]);
            line = scanner.nextLine();
            information = line.split(" ");
            unChokingInterval = Integer.parseInt(information[1]);
            line = scanner.nextLine();
            information = line.split(" ");
            idealChokingInterval = Integer.parseInt(information[1]);
            line = scanner.nextLine();
            information = line.split(" ");
            fileName = information[1];
            line = scanner.nextLine();
            information = line.split(" ");
            fileSize = Integer.parseInt(information[1]);
            line = scanner.nextLine();
            information = line.split(" ");
            pieceSize = Integer.parseInt(information[1]);
            scanner.close();
        } catch (Exception e) {
            System.out.println("Failed to open Common.cfg");
        }
        try {// Store all neighbor information up to the peer running this program. Will need to pass this into peer construction
            File peerNeighborInfo = new File("./PeerInfo.cfg");
            String peerNeighborInfoString = Files.readString(peerNeighborInfo.toPath());
            scanner = new Scanner(peerNeighborInfoString);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                String[] columnSections = line.split(" ");
                if (columnSections.length == 4) {
                    peerNeighborInfoFromConfig.addElement(new NeighborInfo(Integer.parseInt(columnSections[0]), columnSections[1], Integer.parseInt(columnSections[2]), Integer.parseInt(columnSections[3])));
                }
            }
            scanner.close();
        } catch (Exception e) {
            System.out.println("Failed to open PeerInfo.cfg");
        }

        if(index == -1)  {
            throw new Exception("invalid ID");
        }

        Peer peer = new Peer(peerNeighborInfoFromConfig.get(index).id,
                numPreferredNeighbors,unChokingInterval,idealChokingInterval,fileName,
                fileSize,pieceSize,peerNeighborInfoFromConfig.get(index).port,
                peerNeighborInfoFromConfig.get(index).hasFile, peerNeighborInfoFromConfig);
    }
}