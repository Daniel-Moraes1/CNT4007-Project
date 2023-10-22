package src;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.BitSet;
import java.util.List;
import java.net.*;
import java.io.*;


public class peer {

    private BitSet myBitField;
    private List <neighbor> neighbors;
    private int countFinishedNeighbors;
    private int maxConnections;
    private int unchokingInterval;
    private int optimisticUnchokingInterval;
    private String fileName;
    private int fileSize;
    private int pieceSize;
    private int totalPieces;
    private ServerSocket welcomeSocket;
    private boolean listening;

    public class neighbor {
        public int neighborID;
        public BitSet bitfield;
        public boolean choked;
        public boolean finished;
        Socket connection;

        public neighbor(int neighborID, String neighborName, int welcomeSocket) throws IOException, ClassNotFoundException {
            this.neighborID = neighborID;
            this.bitfield = new BitSet(totalPieces);
            this.choked = true;
            this.finished = false;
            this.connection = fetchPort(neighborName, welcomeSocket);
        }
        public neighbor(int neighborID, String neighborName, int welcomeSocket, ) throws IOException, ClassNotFoundException {
            this.neighborID = neighborID;
            this.bitfield = new BitSet(totalPieces);
            this.choked = true;
            this.finished = false;
            this.connection = fetchPort(neighborName, welcomeSocket);
        }

        private Socket fetchPort(String neighborName, int welcomeSocket) throws IOException, ClassNotFoundException {
            Socket tempSocket = new Socket(neighborName, welcomeSocket);
            ObjectInputStream in = new ObjectInputStream(tempSocket.getInputStream());
            int portNumber = in.readInt();
            tempSocket.close();
            return new Socket(neighborName,  portNumber);
        }

        private void listenForNewNeighbor() throws Exception {
            try {
                while(listening) {
                    welcomeSocket.accept();

                    ServerSocket s = new ServerSocket(0);
                    int port = s.getLocalPort();
                    ObjectOutputStream out;
                    out = new ObjectOutputStream(connection.getOutputStream());
                    out.writeInt(port);

                    Thread connectionThread = new Thread(() -> {

                        this.makeNewConnection(s);

                    });
                    connectionThread.start();
                    connectionThread.join(10000); // Allow 10 seconds for neighbor to connect


                }
            }
            catch(Exception e) {
                throw new Exception("Welcome socket failed");
            }
        }

    }

    public peer(int maxConnections_, int unchokingInterval_,
                int optimisticUnchokingInterval_, String fileName_,
                int fileSize_, int pieceSize_, int welcomePort_) {
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
    }

    public void makeNewConnection (ServerSocket s) throws IOException {
        s.setSoTimeout(10000);
        try {
            s.accept();
        }
        catch(SocketTimeoutException e) {
            throw new SocketTimeoutException("Client socket failed to connect to new socket");
        }  catch (IOException e) {
            throw new IOException("IO Exception occurred while waiting for client to connect.");
        }

        s.setSoTimeout(0);
        neighbor n = new neighbor()
        Thread readerThread = new Thread(() -> {
            this.readFromNeighbor(neighbor);
        });
        readThread.start();
        Thread writerThread = new Thread(() -> {
            this.writeToNeighbor(neighbor);
        });
        writerThread.start();
    }


}


