package src;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Peer {

    private volatile int id;
    private volatile BitSet bitfield;
    private volatile BitSet requested;
    private volatile ArrayList <Neighbor> neighbors;
    private volatile HashSet <Neighbor> unchokedNeighbors;
    private volatile HashSet <Neighbor> chokedNeighbors;
    private volatile int countFinishedNeighbors;
    private volatile int numNeighbors;
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
    private volatile Thread timerThread;
    private volatile long lastUnchoke;
    private volatile long lastOptimisticUnchoke;
    public volatile Neighbor optimisticUnchokedNeighbor;
    public Log logObj;
    public Lock chokeLock;

    public class Neighbor {
        public volatile int id;
        public volatile String address;
        public volatile int welcomePort;
        public volatile int packetCount;
        public volatile BitSet bitfield;
        public volatile int numPieces;
        public volatile boolean finished;
        public volatile Socket connection;
        public volatile boolean  interestedInPeer; // Is neighbor interested in Peer's pieces
        public volatile boolean  interestedInNeighbor; // Is Peer interested in neighbor's pieces
        public volatile boolean chokingPeer; // Is Peer choked by neighbor
        public volatile HashSet<Integer> piecesForPeer; // Track pieces neighbor has that peer does not have
        public volatile boolean waitingForPiece;
        public volatile int piecesInInterval;
        public volatile Thread requestThread;
        public volatile Thread initiatorThread;
        public Lock writeLock;
        public Lock piecesForPeerLock;

        public Neighbor(int id, Socket connection_) throws IOException, ClassNotFoundException {
            this.id = id;
            this.bitfield = new BitSet(totalPieces);
            this.bitfield.set(0, bitfield.size(), false); // Assume other peers have nothing until bitfield is sent
            this.finished = false;
            this.connection = connection_;
            this.interestedInPeer = false;
            this.interestedInNeighbor = false;
            this.chokingPeer = true; // Default to choking
            this.packetCount = 0;
            this.piecesForPeer = new HashSet<Integer>();
            this.waitingForPiece = false;
            this.piecesInInterval = 0;
            this.numPieces = 0;
            this.writeLock = new ReentrantLock();
            this.piecesForPeerLock = new ReentrantLock();
        }
    }

    public Peer(int id_, int numNeighbors, int maxConnections_, int unchokingInterval_,
                int optimisticUnchokingInterval_, String fileName_,
                long fileSize_, long pieceSize_, int welcomePort_, boolean hasFile_, Vector<NeighborInfo> neighborInfo)
                throws Exception {
        this.id = id_;
        this.numNeighbors = numNeighbors;
        this.logObj = new Log(this.id);
        this.neighbors = new ArrayList<Neighbor>();
        this.unchokedNeighbors = new HashSet<Neighbor>();
        this.chokedNeighbors = new  HashSet <Neighbor>();
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
            this.bitfield.set(0, totalPieces, true);
            this.numPieces = totalPieces;
            this.finished = true;
        }
        this.chokeLock = new ReentrantLock();

        this.p2pFile = new P2PFile("Config/" + id + "/" + fileName_, fileSize, pieceSize,hasFile_);
        connectToNeighbors(neighborInfo);
        createWelcomeThread();
        createTimerThread();
    }

    private Socket connectToServer(NeighborInfo neighborInfo) throws Exception {
        System.out.println("Attempting to connect to neighbor " + neighborInfo.id);
        Socket tempSocket = new Socket(neighborInfo.name, neighborInfo.port);
        ObjectOutputStream out = new ObjectOutputStream(tempSocket.getOutputStream());
        out.flush();
        ObjectInputStream in = new ObjectInputStream(tempSocket.getInputStream());
        System.out.println("Listening for port number from server...");
        int portNumber = (int)in.readObject();
        System.out.println("Received port number " + portNumber);
        tempSocket.close();
        System.out.println("Closed temp socket, looking to connect to new port...");
        Socket newSocket = new Socket(neighborInfo.name, portNumber);
        System.out.println("Successfully connected to neighbor " + neighborInfo.id + " on port " + portNumber);
        handShakeClient(newSocket);
        Neighbor n = new Neighbor(neighborInfo.id, newSocket);
        this.neighbors.add(n);
        chokeLock.lock();
        try {
            this.chokedNeighbors.add(n);
        } finally {
            chokeLock.unlock();
        }
        createNeighborThreads(n);
        sendBitfield(n);
        return newSocket;
    }

    public void connectToClient (ServerSocket s) throws Exception {
        System.out.println("In make new connection thread");
        s.setSoTimeout(10000);
        Socket connection;
        try {
            connection = s.accept();
            System.out.println("Successfully accepted connection on new port");
        }
        catch(SocketTimeoutException e) {
            throw new SocketTimeoutException("Client socket failed to connect to new socket");
        }  catch (IOException e) {
            throw new IOException("IO Exception occurred while waiting for client to connect.");
        }


        System.out.println("Starting call to handshake");
        int id = handShakeServer(connection);
        Neighbor n = new Neighbor(id, connection);
        logObj.logConnectedFrom(this.id, n.id);
        neighbors.add(n);
        chokeLock.lock();
        try {
            this.chokedNeighbors.add(n);
        } finally {
            chokeLock.unlock();
        }
        System.out.println("Starting call to create neighbor threads");
        createNeighborThreads(n);
        System.out.println("Out of createNeighborThreads");
        sendBitfield(n);
    }

    private void listenForNewNeighbor() throws Exception {
        try {
            while(listening) {
                System.out.println("Listening for new neighbors...");
                Socket connection = welcomeSocket.accept(); // welcome socket connection
                System.out.println("New neighbor connected");

                ServerSocket s = new ServerSocket(0);

                //Start listening on new server socket
                Thread connectionThread = new Thread(() -> {
                    try {
                        this.connectToClient(s);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                connectionThread.start();

                int port = s.getLocalPort();
                ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(connection.getInputStream());
                System.out.println("Sending port number " + port);
                out.writeObject(port); // write port number through welcome socket connection
                out.flush();
                connectionThread.join();

                //logObj.logConnectedFrom(this.id);
            }
        }
        catch(Exception e) {
            throw new Exception("Welcome socket failed");
        }
    }

    public void connectToNeighbors(Vector<NeighborInfo> neighborInfo) throws Exception {
        for (int i=0; i<neighborInfo.size(); i++) {
            connectToServer(neighborInfo.get(i));
        }
    }


    public int handShakeServer(Socket s) throws Exception {
        // Project spec does not specify that a handshake ACK should come through
        // Leaving it out for now.
        ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
        out.flush();
        ObjectInputStream in = new ObjectInputStream(s.getInputStream());
        System.out.println("Waiting for client to send handshake...");
        String response = (String)in.readObject();
        int neighborId = Integer.parseInt(response.substring(28));
        if (neighborId == -1) {
            throw new Exception("Received invalid neighbor ID from handshake");
        }
        if (!response.substring(0,28).equals("P2PFILESHARINGPROJ" + "          ")) {
            throw new Exception("Received wrong connection message from client: " + response);
        }
        System.out.println("Response from peer: " + response);
        System.out.println(neighborId);

        System.out.println("Writing: " + "P2PFILESHARINGPROJ" + "          " + Integer.toString(this.id));
        out.writeObject("P2PFILESHARINGPROJ" + "          " + Integer.toString(this.id));
        return neighborId;
    }

    public void handShakeClient(Socket s) throws Exception {
        ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
        out.flush();
        ObjectInputStream in = new ObjectInputStream(s.getInputStream());
        System.out.println("Writing: " + "P2PFILESHARINGPROJ" + "          " + Integer.toString(this.id));
        out.writeObject("P2PFILESHARINGPROJ" + "          " + Integer.toString(this.id));
        String response = (String)in.readObject();
        if (!response.substring(0,28).equals("P2PFILESHARINGPROJ" + "          ")) {
            // Incorrect response from neighbor, need to handle
            throw new Exception("Received wrong connection message from client: " + response);
        }
        System.out.println("Response from peer: " + response);
    }

    public void createNeighborThreads(Neighbor n) throws Exception {
        System.out.println("In createNeighborThreads");
        n.requestThread = new Thread(() -> {
            try {
                this.responder(n);
            } catch (Exception e) {
                System.out.println("Thread interrupted");
                e.printStackTrace();
            }
        });
        System.out.println("Starting request thread");
        n.requestThread.start();
        n.initiatorThread = new Thread(() -> {
            try {
                this.initiator(n);
            } catch (Exception e) {
                System.out.println("Thread interrupted");
                e.printStackTrace();
            }
        });
        System.out.println("Starting initiator thread");
        n.initiatorThread.start();
    }

    public void createWelcomeThread() throws InterruptedException {
        this.welcomeThread = new Thread(() -> {
            try {
                this.listenForNewNeighbor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        welcomeThread.start();
    }
    public void createTimerThread() throws InterruptedException {
        System.out.println("In createTimerThread");
        this.timerThread = new Thread(() -> {
            try {
                this.timer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        this.timerThread.start();
    }

    // Thread for reading from neighbor connection
    public void responder(Neighbor neighbor) throws Exception {
        // For each message, get message type
        int count = 0; // DELETE ME
        InputStream in = neighbor.connection.getInputStream();
        while(listening) {
            byte[] lengthBytes = in.readNBytes(4); // Wait for message to come in
            int messageLength = Util.fourBytesToInt(lengthBytes);

            int type = in.read();
            switch(type) {
                // Choke
                // Stop receiving messages from neighbor
                case 0:
                    neighbor.chokingPeer = true;
                    System.out.println("Choked by " + neighbor.id);
                    break;

                // Unchoke
                // Begin receiving messages from neighbor
                case 1:
                    neighbor.chokingPeer = false;
                    System.out.println("Unchoked by " + neighbor.id);
                    break;

                //Interested
                case 2:
                    neighbor.interestedInPeer = true;
                    System.out.println(neighbor.id + " is interested in us");
                    break;

                // Not Interested
                case 3:
                    neighbor.interestedInPeer = false;
                    System.out.println(neighbor.id + " is not interested in us");
                    break;

                // Have
                case 4:
                    byte[] byteIndex = in.readNBytes(4);
                    count++;
                    int index = Util.fourBytesToInt(byteIndex);
                    System.out.println("Received HAVE " + index + " from " + neighbor.id);
                    System.out.println("Count = " + count + ". NumPieces = " + neighbor.numPieces);
                    if (!neighbor.bitfield.get(index)) {
                        neighbor.bitfield.set(index, true);
                        neighbor.numPieces++;
                        if (neighbor.numPieces == totalPieces) {
                            neighbor.finished = true;
                            countFinishedNeighbors++;
                            System.out.println("Neighbor " + neighbor.id + " is finished. Total completed neighbors = " + countFinishedNeighbors);
                            if (checkDone()) return;
                        }
                    }
                    if (!this.bitfield.get(index)) {
                        neighbor.piecesForPeerLock.lock();
                        try {
                            neighbor.piecesForPeer.add(index);
                        } finally {
                            neighbor.piecesForPeerLock.unlock();
                        }
                        sendMessage(MessageType.INTERESTED, neighbor, null);
                        System.out.println("Sending interested message to " + neighbor.id);
                    }
                    break;

                // Bitfield
                case 5:
                    byte[] bitfieldBytes = in.readNBytes(messageLength-1);
                    neighbor.bitfield = bytesToBitSet(bitfieldBytes);
                    neighbor.piecesForPeerLock.lock();
                    try {
                        for (int i=0; i<neighbor.bitfield.size(); i++) {
                            if (neighbor.bitfield.get(i)) {
                                neighbor.numPieces++;
                                if (!this.bitfield.get(i)) {
                                    neighbor.piecesForPeer.add(i);
                                }
                            }
                        }

                    } finally {
                        neighbor.piecesForPeerLock.unlock();
                    }
                    if (neighbor.numPieces == totalPieces) {
                        neighbor.finished = true;
                        countFinishedNeighbors++;
                        System.out.println("Neighbor " + neighbor.id + " is finished. Total completed neighbors = " + countFinishedNeighbors);
                        if (checkDone()) return;
                    }
                    checkInterestInNeighbor(neighbor);

                    if (neighbor.interestedInNeighbor) {
                        logObj.logReceivedInterested(this.id,neighbor.id);
                        sendMessage(MessageType.INTERESTED, neighbor, null); // Send interested in neighbor
                        System.out.println("Sent INTERESTED message to " + neighbor.id);
                    }
                    else {
                        logObj.logReceivedNotInterested(this.id,neighbor.id);
                        sendMessage(MessageType.NOT_INTERESTED, neighbor, null); // Send not interested in neighbor
                        System.out.println("Sent NOT_INTERESTED message to " + neighbor.id);
                    }
                    break;

                // Request
                case 6:
                    byte[] requestIndexBytes = in.readNBytes(4);
                    int requestedIndex = Util.fourBytesToInt(requestIndexBytes);
                    System.out.println("Received request for piece " + requestedIndex);
                    Boolean unchoked = false;
                    chokeLock.lock();
                    try {
                        unchoked = unchokedNeighbors.contains(neighbor);
                    } finally {
                        chokeLock.unlock();
                    }
                    if (unchoked) {
                        System.out.println("Sending piece message to " + neighbor.id);
                        sendMessage(MessageType.PIECE, neighbor, p2pFile.getPiece(requestedIndex));
                        neighbor.piecesInInterval++;
                    }
                    else {
                        // We can send an empty piece for the request index if the neighbor has been choked
                        // This will let then know to request the piece from another neighbor
                        sendMessage(MessageType.PIECE, neighbor, requestIndexBytes);
                        System.out.println("Sending piece empty piece " + requestedIndex);
                    }
                    break;

                // Piece
                case 7:
                    // The first four bytes of a piece payload is the index
                    byte[] pieceIndexBytes = in.readNBytes(4);
                    int pieceIndex = Util.fourBytesToInt(pieceIndexBytes);
                    System.out.println("Received piece " + pieceIndex);
                    if (messageLength == 5) {
                        // Piece was not sent over (neighbor does not have or we have been choked)
                        requested.set(pieceIndex, false);
                        break;
                    }
                    else {
                        byte[] pieceData = in.readNBytes(messageLength-5);
                        p2pFile.writePiece(pieceIndex, pieceData);
                        if (!this.bitfield.get(pieceIndex)) {
                            this.bitfield.set(pieceIndex, true);
                            numPieces++;
                            if (numPieces == totalPieces) {
                                this.finished = true;
                                System.out.println("Finished");
                                if (checkDone()) return;
                            }
                        }


                        // When we get a piece, inform all neighbors that we have that piece. Re-evaluate interest
                        for (int i=0; i<neighbors.size(); i++) {
                            logObj.logReceivedHave(this.id,neighbors.get(i).id,pieceIndex);
                            sendMessage(MessageType.HAVE, neighbors.get(i), pieceIndexBytes);
                            System.out.println("Sending HAVE message to " + neighbors.get(i).id + " for piece " + pieceIndex);

                            // Upon receiving a new packet, remove packet index for set of packets that neighbors have and peer does not
                            // If set of missing packets that a neighbor has becomes 0, send NOT_INTERESTED
                            neighbors.get(i).piecesForPeerLock.lock();
                            try {
                                if (neighbors.get(i).piecesForPeer.contains(pieceIndex)) {
                                    neighbors.get(i).piecesForPeer.remove(pieceIndex);
                                    if (!checkInterestInNeighbor(neighbors.get(i))) {
                                        logObj.logReceivedNotInterested(this.id,neighbors.get(i).id);
                                        sendMessage(MessageType.NOT_INTERESTED, neighbor, null);
                                        System.out.println("Sending not interested message to " + neighbors.get(i).id);
                                    }
                                }
                            } finally {
                                neighbors.get(i).piecesForPeerLock.unlock();
                            }
                        }
                    }
                    requested.set(pieceIndex, false);
                    neighbor.waitingForPiece = false;
                    break;
                default: {
                        System.out.println("Received invalid message type");
                }
            }
        }
    }

    // Thread for starting message sends to neighbors (piece requests)
    public void initiator(Neighbor neighbor) throws Exception {
        System.out.println("In initiator thread");
        OutputStream out = neighbor.connection.getOutputStream();
        while(listening) {
            if (!neighbor.chokingPeer) {
                if (neighbor.interestedInNeighbor ) {
                    if (!neighbor.waitingForPiece) {
                        //If we are not choked by the neighbor, neighbor has pieces we do not,
                        // and we do not have an outstanding piece request to neighbor, find a piece to request
                        neighbor.piecesForPeerLock.lock();
                        if (neighbor.piecesForPeer.size() == 0) {
                            continue;
                        }
                        int pieceNumber = -1;
                        try {
                            int random = new Random().nextInt(neighbor.piecesForPeer.size());
                            int count = 0;
                            pieceNumber = 0;
                            for (int iterator : neighbor.piecesForPeer) {
                                if (count == random) {
                                    pieceNumber = iterator;
                                    break;
                                }
                                count++;
                            }
                            if (requested.get(pieceNumber)) {
                                continue; // Don't request the same piece twice
                            }
                        } finally {
                            neighbor.piecesForPeerLock.unlock();
                        }
                        byte[] pieceNumberBytes = Util.intToFourBytes(pieceNumber);
                        sendMessage(MessageType.REQUEST, neighbor, pieceNumberBytes);
                        System.out.println("Sent piece request for piece " + pieceNumber);
                        neighbor.waitingForPiece = true;
                    }
                }
            }
        }
    }

    // add Locks / Mutexes here if possible
    public void timer() throws IOException {
        while(listening) {
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

    private void sendBitfield(Neighbor n) throws IOException {
        if (this.numPieces == 0) {
            return; // Don't send bitfield message if peer has no pieces
        }
        byte[] bytes = bitfield.toByteArray();
        sendMessage(MessageType.BITFIELD, n, bytes);
    }

    private void unchoke() throws IOException {
        // Find peers with greatest download rates
        // We can just sort for now, but not best TC
        chokeLock.lock();
        try {
            Vector<Neighbor> interestedNeighbors = new Vector<Neighbor>();
            for (int i=0; i<neighbors.size(); i++) {
                if (neighbors.get(i).interestedInPeer) {
                    interestedNeighbors.add(neighbors.get(i));
                    logObj.logReceivedInterested(this.id, neighbors.get(i).id);
                }
            }
            if (interestedNeighbors.size() == 0) {
                return;
            }
            Collections.sort(interestedNeighbors, new SortByDownloadRate());
            Vector<Neighbor> toUnchoke = new Vector<Neighbor>();
            Vector<Neighbor> toChoke = new Vector<Neighbor>();
            for (int i=0; i<interestedNeighbors.size(); i++) {
                if (toUnchoke.size() == maxConnections) {
                    break;
                }
                if (i == interestedNeighbors.size()-1) {
                    toUnchoke.add(interestedNeighbors.get(i));
                    break;
                }
                if (interestedNeighbors.get(i).piecesInInterval > interestedNeighbors.get(i+1).piecesInInterval) {
                    toUnchoke.add(interestedNeighbors.get(i));
                }
                else if (interestedNeighbors.get(i).piecesInInterval == interestedNeighbors.get(i+1).piecesInInterval) {
                    Vector<Neighbor> tie = new Vector<Neighbor>();
                    while (i < interestedNeighbors.size()-1 && interestedNeighbors.get(i).piecesInInterval == interestedNeighbors.get(i+1).piecesInInterval) {
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
                            int index = new Random().nextInt(tie.size());
                            toUnchoke.add(tie.get(index));
                            tie.remove(index);
                        }
                    }
                }
            }

            // If an unchoked neighbor is not reselected to be unchoked

            for (Neighbor n : unchokedNeighbors) {
                if (!toUnchoke.contains(n) && this.optimisticUnchokedNeighbor != n) {
                    toChoke.add(n);
                }
            }

            for (Neighbor n : toChoke) {
                if (unchokedNeighbors.contains(n)) {
                    unchokedNeighbors.remove(n);
                    chokedNeighbors.add(n);
                    logObj.logChoked(this.id,n.id);
                    sendMessage(MessageType.CHOKE, n, null);
                    System.out.println("Choking neighbor " + n.id);
                }
            }

            for (Neighbor n : toUnchoke) {
                if (chokedNeighbors.contains(n)) {
                    chokedNeighbors.remove(n);
                    unchokedNeighbors.add(n);
                    logObj.logUnchoked(this.id,n.id);
                    sendMessage(MessageType.UNCHOKE, n, null);
                    System.out.println("Unchoked neighbor " + n.id);
                }
                if (n == this.optimisticUnchokedNeighbor) {
                    this.optimisticUnchokedNeighbor = null;
                }
            }



        } finally {
            chokeLock.unlock();
        }

        // Reset pieces downloaded in interval for all neighbors
        for (int i=0; i<neighbors.size(); i++) {
            neighbors.get(i).piecesInInterval = 0;
        }
    }
    private void optimisticUnchoke() throws IOException {
        Vector<Neighbor> interested = new Vector<Neighbor>();
        chokeLock.lock();
        try {
            for (Neighbor n : chokedNeighbors) {
                if (n.interestedInPeer) {
                    interested.add(n);
                }
            }

            if (interested.size() != 0) {
                int random = new Random().nextInt(interested.size());
                Neighbor n = interested.get(random);
                if (optimisticUnchokedNeighbor != null && n == optimisticUnchokedNeighbor) {
                    return; // Do not send any messages if same optmistic unchoke neighbor is selected
                }
                else if (optimisticUnchokedNeighbor != null && n != optimisticUnchokedNeighbor) {
                    unchokedNeighbors.remove(optimisticUnchokedNeighbor);
                    chokedNeighbors.add(optimisticUnchokedNeighbor);
                    sendMessage(MessageType.CHOKE, optimisticUnchokedNeighbor, null);
                    System.out.println("Choking optimistically unchoked neighbor " + optimisticUnchokedNeighbor.id);
                    logObj.logOptimisticallyUnchokedNeighbor(this.id,optimisticUnchokedNeighbor.id);
                }
                optimisticUnchokedNeighbor = n;
                unchokedNeighbors.add(n);
                chokedNeighbors.remove(n);
                sendMessage(MessageType.UNCHOKE, n, null);
                System.out.println("Optimistically unchoking neighbor " + interested.get(random).id);
            }
            else {
                if (optimisticUnchokedNeighbor != null) {
                    unchokedNeighbors.remove(optimisticUnchokedNeighbor);
                    chokedNeighbors.add(optimisticUnchokedNeighbor);
                    sendMessage(MessageType.CHOKE, optimisticUnchokedNeighbor, null);
                    System.out.println("Choking optmistically unchoked neighbor " + optimisticUnchokedNeighbor + " (not interested in us or unchoked)");
                }
            }
        } finally {
            chokeLock.unlock();
        }


    }

    private void sendMessage(MessageType messageType, Neighbor n, byte[] message) throws IOException {
        OutputStream out = n.connection.getOutputStream();
        int messageLength = message != null ? message.length + 1 : 1;
        byte[] messageLengthBytes = Util.intToFourBytes(messageLength);
        byte type = -1;
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
        byte[] fullMessage = new byte[4+messageLength];
        System.arraycopy(messageLengthBytes, 0, fullMessage, 0, 4);
        fullMessage[4] = type;
        if (message != null) {
            System.arraycopy(message, 0, fullMessage, 5, message.length);
        }

        n.writeLock.lock();
        try {
            out.write(fullMessage);
            out.flush();
        } finally {
            n.writeLock.unlock();
        }
    }

    private boolean checkInterestInNeighbor(Neighbor neighbor) {
        neighbor.interestedInNeighbor = !neighbor.piecesForPeer.isEmpty();
        return neighbor.interestedInNeighbor;
    }

    // If all neighbors and self is done, end all connections with Peer
    private boolean checkDone() throws IOException {
        return false; // DELETEME
        /*
        if (this.countFinishedNeighbors == numNeighbors && this.finished) {
            listening = false;
            System.out.println("All neighbors are finished, terminating and ending all threads");
            shutDown();
            return true;
        }
        return false;*/
    }

    private void shutDown() throws IOException {
        this.welcomeThread.interrupt();
        this.timerThread.interrupt();
        for (int i=0; i<neighbors.size(); i++) {
            //neighbors.get(i).connection.close();
            neighbors.get(i).initiatorThread.interrupt();
            neighbors.get(i).requestThread.interrupt();
        }
        System.out.println("Shutting down");
    }


    // Not sure if this works
    class SortByDownloadRate implements Comparator<Neighbor> {
        public int compare(Neighbor a, Neighbor b) {
            return a.piecesInInterval - b.piecesInInterval;
        }
    }

    //Bytes come in as little-endian
    public BitSet bytesToBitSet(byte[] bytes) {
        BitSet bitSet = new BitSet();
        for (int i=0; i<bytes.length; i++) {
            for (int j=0; j<8; j++) {
                if ((bytes[i] & 1) > 0) {
                    if (i*8+j >= totalPieces) {
                        continue;
                    }
                    bitSet.set(i*8+j);
                }
                bytes[i] = (byte)(bytes[i] >> 0x1);
            }
        }
        return bitSet;
    }

    public static void main(String[] args) throws Exception {
        int id = -1;
        Scanner scanner;
        Vector<NeighborInfo> peerNeighborInfoFromConfig = new Vector<NeighborInfo>();
        int numPreferredNeighbors = -1, unChokingInterval = -1, optimisticUnChokingInterval = -1;
        String fileName = "";
        long fileSize = -1; //will need to be able to store large numbers
        long pieceSize = -1;
        try {
            id = Integer.parseInt(args[0]);
        } catch (Exception e) {
            System.out.println("Invalid input ID");
        }
        try { // Read config info from common.
            File common = new File("./Config/Common.cfg");
            scanner = new Scanner(common);
            String line = scanner.nextLine();
            String[] information = line.split(" ");
            numPreferredNeighbors = Integer.parseInt(information[1]);
            line = scanner.nextLine();
            information = line.split(" ");
            unChokingInterval = Integer.parseInt(information[1]);
            line = scanner.nextLine();
            information = line.split(" ");
            optimisticUnChokingInterval = Integer.parseInt(information[1]);
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
            throw new Exception("Failed to open Common.cfg");
        }
        int count = 0;
        boolean found = false;
        NeighborInfo peerInfo = new NeighborInfo(-1, "-1", -1, -1);
        try {// Store all neighbor information up to the peer running this program. Will need to pass this into peer construction
            File peerNeighborInfo = new File("./Config/PeerInfo.cfg");
            scanner = new Scanner(peerNeighborInfo);
            String line;
            while (scanner.hasNextLine()) {
                line = scanner.nextLine();


                String[] columnSections = line.split(" ");
                if (columnSections.length == 4) {
                    count++;
                    if (found) continue;
                    int neighborID = Integer.parseInt(columnSections[0]);
                    if (neighborID == id) {
                        peerInfo = new NeighborInfo(neighborID, columnSections[1], Integer.parseInt(columnSections[2]), Integer.parseInt(columnSections[3]));
                        found = true;
                    }
                    else {
                        peerNeighborInfoFromConfig.addElement(new NeighborInfo(neighborID, columnSections[1], Integer.parseInt(columnSections[2]), Integer.parseInt(columnSections[3])));
                    }
                }
            }
            scanner.close();
        } catch (Exception e) {
            throw new Exception("Failed to open PeerInfo.cfg");
        }

        if(id == -1)  {
            throw new Exception("invalid ID");
        }

        Peer peer = new Peer(peerInfo.id, count-1,
                numPreferredNeighbors,unChokingInterval,optimisticUnChokingInterval,fileName,
                fileSize,pieceSize,peerInfo.port,
               peerInfo.hasFile, peerNeighborInfoFromConfig);
    }
}