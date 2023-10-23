package src;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.BitSet;
import java.util.List;
import java.net.*;
import java.io.*;



public class Peer {

    private int id;
    private BitSet bitfield;
    private BitSet requested;
    private List <Neighbor> neighbors;
    private int countFinishedNeighbors;
    private int maxConnections;
    private int unchokingInterval;
    private int optimisticUnchokingInterval;
    private String fileName;
    private int fileSize;
    private int pieceSize;
    private int totalPieces;
    private ServerSocket welcomeSocket;
    private boolean finished;
    private boolean listening;

    public class Neighbor {
        public int id;
        public String address;
        public int packetCount;
        public BitSet bitfield;
        public boolean choked;
        public boolean finished;
        Socket connection;
        Status readStatus; // Interested / Not Interested in a neighbor
        Status writeStatus; // Is a neighbor choked or unchoked by us


        public Neighbor(int id_, String address_, int welcomeSocket) throws IOException, ClassNotFoundException {
            this.id = id_;
            this.address = address_;
            this.bitfield = new BitSet(totalPieces);
            this.choked = true;
            this.finished = false;
            this.connection = fetchPort(address_, welcomeSocket);
            this.readStatus = Status.CHOKE;
            this.writeStatus = Status.CHOKE;
            this.packetCount = 0;
            this.finished = false;
        }
        public Neighbor(int id, Socket connection_) throws IOException, ClassNotFoundException {
            this.id = id;
            this.bitfield = new BitSet(totalPieces);
            this.choked = true;
            this.finished = false;
            this.connection = connection_;
            this.readStatus = Status.CHOKE;
            this.writeStatus = Status.CHOKE;
            this.packetCount = 0;
            this.finished = false;
        }

        private Socket fetchPort(String neighborName, int welcomeSocket) throws IOException, ClassNotFoundException {
            Socket tempSocket = new Socket(neighborName, welcomeSocket);
            ObjectInputStream in = new ObjectInputStream(tempSocket.getInputStream());
            int portNumber = in.readInt();
            tempSocket.close();
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

        Thread readerThread = new Thread(() -> {
            try {
                this.readFromNeighbor(n);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        readerThread.start();
        readerThread.join();
        Thread writerThread = new Thread(() -> {
            this.writeToNeighbor(n);
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
    public void readFromNeighbor(Neighbor neighbor) throws Exception {
        // For each message, get message type
        InputStream in = neighbor.connection.getInputStream();
        OutputStream out = neighbor.connection.getOutputStream();
        byte[] lengthBytes = in.readNBytes(4);

        int messageLength = fourBytesToInt(lengthBytes);

        int type = in.read();
        switch(type) {
            // Choke
            // Stop receiving messages from neighbor
            case 0:
                neighbor.readStatus = Status.CHOKE;
                break;

            // Unchoke
            // Begin receiving messages from neighbor
            case 1:
                neighbor.readStatus = Status.UNCHOKE;
                break;

            //Interested
            case 2:
                neighbor.writeStatus = Status.INTERESTED;

                break;

            // Not Interested
            case 3:
                neighbor.writeStatus = Status.NOT_INTERESTED;
                break;

            // Have
            case 4:
                byte[] byteIndex = in.readNBytes(4);

                int index = fourBytesToInt(byteIndex);
                if (!this.bitfield.get(index)) {
                    sendMessage(Status.INTERESTED, out, null);
                }
                else {
                    byte[] empty;
                    sendMessage(Status.NOT_INTERESTED, out, null);
                }
                break;

            // Bitfield
            // Upon receiving
            case 5:
                // Probably need locks here
                byte[] bitfieldBytes = in.readNBytes(messageLength-1);
                neighbor.bitfield = BitSet.valueOf(bitfieldBytes);
                neighbor.readStatus = Status.NOT_INTERESTED;
                for (int i=0; i<bitfield.size(); i++) {
                    // Neighbor has pieces we don't have and have not requested
                    if (neighbor.bitfield.get(i) && !this.bitfield.get(i) && !this.requested.get(i)) {
                        neighbor.readStatus = Status.INTERESTED;
                    }
                }
                sendMessage(neighbor.readStatus, out, null); // State whether interested or not in neighbor
                break;

            // Request
            case 6:
                byte[] requestIndexBytes = in.readNBytes(4);
                int requestedIndex = fourBytesToInt(requestIndexBytes);
                if (!neighbor.choked) {
                    // If the neighbor is not choked, send the piece
                    sendMessage(Status.PIECE, out, NEEDED PIECE);
                }
                else {
                    // We can send an empty piece if the neighbor has been choked
                    // This will let then know to request the piece from another neighbor
                    sendMessage(Status.PIECE, out, null);
                }
                break;

            // Piece
            case 7:
                byte[] pieceBytes = in.readNBytes(messageLength-1);
                if (pieceBytes == null) {
                    // We were choked by the neighbor and the piece was not sent over
                    requestedIndex.set

                }
                break;
        }


    }

    // Thread for writing to neighbor connection
    public void writeToNeighbor(Neighbor neighbor) {
        if (!this.finished) {
            if (neighbor.bitfield - this.bitfield)

        }

    }

    private void sendMessage(Status status, OutputStream out, byte[] message) {

    }

    public int fourBytesToInt(byte[] bytes) {
        int sum = 0;
        for (byte b: bytes) {
            sum = (sum << 8) + b;
        }
        return sum;
    }


}


