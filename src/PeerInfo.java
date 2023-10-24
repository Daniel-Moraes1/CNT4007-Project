package src;

public class PeerInfo {
    int numPreferredNeighbors;
    int unChokingInterval;
    int idealChokingInterval;
    String fileName;
    long fileSize; //will need to be able to store large numbers
    long pieceSize;

    public PeerInfo(int numPreferred, int uChokingInt, int idealChokingInt, String name, int fileSz, int pieceSz)
    {
        this.numPreferredNeighbors = numPreferred;
        this.unChokingInterval = uChokingInt;
        this. idealChokingInterval = idealChokingInt;
        this.fileName = name;
        this.fileSize = fileSz;
        this.pieceSize = pieceSz;
    }
}
