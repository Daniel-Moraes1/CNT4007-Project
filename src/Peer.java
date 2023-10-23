package src;
import src.P2PFile;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.BitSet;
import java.util.List;
import java.net.*;
import java.io.*;
import java.util.HashSet;
import java.util.Random;


public class Peer {

    private volatile int id;
    private volatile BitSet bitfield;
    private volatile BitSet requested;
    private volatile List <Neighbor> neighbors;
    private volatile int countFinishedNeighbors;
    private volatile int numConnections;
    private volatile int maxConnections;
    private volatile int unchokingInterval;
    private volatile int optimisticUnchokingInterval;
    private volatile String fileName;
    private volatile int fileSize;
    private volatile int pieceSize;
    private volatile int totalPieces;
    private volatile ServerSocket welcomeSocket;
    private volatile boolean finished;
    private volatile boolean listening;
    private volatile P2PFile p2pFile;

    public class Neighbor {
        public volatile int id;
        public volatile String address;
        public volatile int packetCount;
        public volatile BitSet bitfield;
        public volatile boolean choked;
        public volatile boolean finished;
        public volatile Socket connection;
        public volatile boolean  interestedInPeer; // Is neighbor interested in Peer's pieces
        public volatile boolean  interestedInNeighbor; // Is Peer interested in neighbor's pieces
        public volatile boolean chokedByPeer; // Is neighbor choked by Peer
        public volatile boolean chokedByNeighbor; // Is Peer choked by neighbor
        public volatile HashSet<Integer> piecesForPeer; // Track pieces neighbor has that peer does not have
        public volatile boolean waitingForPiece;
        public volatile int piecesInInterval;


        public Neighbor(int id_, String address_, int welcomeSocket) throws IOException, ClassNotFoundException {
            this.id = id_;
            this.address = address_;
            this.bitfield = new BitSet(totalPieces);
            this.finished = false;
            this.connection = fetchPort(address_, welcomeSocket);
            this.interestedInPeer = false;
            this.interestedInNeighbor = false;
            this.chokedByPeer = true;
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
            this.chokedByPeer = true;
            this.chokedByNeighbor = true;
            this.packetCount = 0;
            this.piecesForPeer = new HashSet<Integer>();
            this.waitingForPiece = false;
            this.piecesInInterval = 0;
        }

        private Socket fetchPort(String neighborName, int welcomeSocket) throws IOException, ClassNotFoundException {
            Socket tempSocket = new Socket(neighborName, welcomeSocket);
            ObjectInputStream in = new ObjectInputStream(tempSocket.getInputStream());
            int portNumber = in.readInt();
            tempSocket.close();
            Socket newSocket = new Socket(neighborName, portNumber);
            if (numConnections < maxConnections) {
                this.chokedByPeer = false;
                numConnections++;
            }
            return new Socket(neighborName,  portNumber);
        }

    }

    public Peer(int id_, int maxConnections_, int unchokingInterval_,
                int optimisticUnchokingInterval_, String fileName_,
                int fileSize_, int pieceSize_, int welcomePort_, boolean hasFile_) throws IOException {
        this.id = id_;
        this.maxConnections = maxConnections_;
        this.unchokingInterval = unchokingInterval_;
        this.optimisticUnchokingInterval = optimisticUnchokingInterval_;
        this.fileName = fileName_;
        this.fileSize = fileSize_;
        this.pieceSize = pieceSize_;
        this.totalPieces = fileSize_ / pieceSize;
        if (fileSize_ % pieceSize > 0) {
            this.totalPieces++;
        }
        this.welcomeSocket = new ServerSocket(welcomePort_);
        this.listening = true;
        this.bitfield = new BitSet(totalPieces);
        this.requested = new BitSet(totalPieces);
        // If peer has the file set bits for all pieces to true
        if (hasFile_) {
            bitfield.set(0, bitfield.size(), true);
        }
        this.p2pFile = new P2PFile(fileName_, pieceSize);
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
            n.chokedByPeer = false;
            numConnections++;
        }

        Thread readerThread = new Thread(() -> {
            try {
                this.responder(n);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        readerThread.start();
        readerThread.join();
        Thread writerThread = new Thread(() -> {
            try {
                this.initiator(n);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        writerThread.start();
        writerThread.join();
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
        OutputStream out = neighbor.connection.getOutputStream();
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
                        sendMessage(MessageType.INTERESTED, out, null);
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
                        sendMessage(MessageType.INTERESTED, out, null); // Send interested in neighbor
                    }
                    else {
                        sendMessage(MessageType.NOT_INTERESTED, out, null); // Send not interested in neighbor
                    }
                    break;

                // Request
                case 6:
                    byte[] requestIndexBytes = in.readNBytes(4);
                    int requestedIndex = fourBytesToInt(requestIndexBytes);
                    if (!neighbor.choked) {
                        // TODO If the neighbor is not choked, send the piece
                        //sendMessage(MessageType.PIECE, out, NEEDED_PIECE);
                        neighbor.piecesInInterval++;
                    }
                    else {
                        // We can send an empty piece for the request index if the neighbor has been choked
                        // This will let then know to request the piece from another neighbor
                        sendMessage(MessageType.PIECE, out, requestIndexBytes);
                        neighbor.bitfield.set(requestedIndex, true);
                    }
                    break;

                // Piece
                case 7:
                    // The first four bytes of a piece payload is the index
                    byte[] pieceIndexBytes = in.readNBytes(4);
                    int pieceIndex = fourBytesToInt(pieceIndexBytes);
                    if (messageLength == 9) {
                        // We were choked by the neighbor and the piece was not sent over
                        requested.set(pieceIndex, false);
                    }
                    else {
                        byte[] pieceData = in.readNBytes(messageLength-1-4);
                        // TODO SAVE PIECE DATA TO FILE
                        this.bitfield.set(pieceIndex, true);

                        // When we get a piece, inform all neighbors that we have that piece. Re-evaluate interest
                        for (int i=0; i<neighbors.size(); i++) {
                            sendMessage(MessageType.HAVE, out, pieceIndexBytes);

                            // Upon receiving a new packet, remove packet index for set of packets that neighbors have and peer does not
                            // If set of missing packets that a neighbor has becomes 0, send NOT_INTERESTED
                            if (neighbors.get(i).piecesForPeer.contains(pieceIndex)) {
                                neighbors.get(i).piecesForPeer.remove(pieceIndex);
                                if (!checkInterestInNeighbor(neighbors.get(i))) {
                                    sendMessage(MessageType.NOT_INTERESTED, out, null);
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
        if (!this.finished) {
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
                        sendMessage(MessageType.REQUEST, out, pieceNumberBytes);
                        neighbor.waitingForPiece = true;
                    }
                }
            }
        }
    }

    private void sendMessage(MessageType messageType, OutputStream out, byte[] message) throws IOException {
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


    public static void main(String[] args) {
        int index;
        try {
            index = Integer.valueOf(args[0]);
        } catch (Exception e) {
            System.out.println("Invalid input ID");
        }
        try {
            File common = new File("./Common.cfg");
        } catch (Exception e) {
            System.out.println("Failed to open Common.cfg");
        }
        try {
            File peerInfo = new File("./PeerInfo.cfg");
        } catch (Exception e) {
            System.out.println("Failed to open PeerInfo.cfg");
        }
        // TODO
        // Read config info from common.
        // Store all neighbor information up to the peer running this program. Will need to pass this into peer construction
        //Peer peer = new Peer();

    }
}

