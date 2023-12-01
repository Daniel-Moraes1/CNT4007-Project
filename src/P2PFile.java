package src;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.BitSet;
import java.util.HashMap;

public class P2PFile {

    private final String filePath;
    private final long pieceSize;
    private final long fileSize;
    private final HashMap<Integer, byte[]> pieces;
    private final BitSet pieceAvailability;

    public P2PFile(String filePath, long fileSize, long pieceSize, boolean hasFile) throws IOException {
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;
        this.pieceAvailability = new BitSet();
        this.pieces = new HashMap<Integer,byte[]>();
        if(hasFile) initializeFilePieces();
    }

    //Used when the file does not exist on local machine initially
    private void createEmptyFile(File file) throws IOException {
        try {
            RandomAccessFile randfile = new RandomAccessFile(file, "rw");
            randfile.setLength(0); // Create an empty file
            randfile.close();
        } catch (IOException e) {
            throw new IOException("Error creating an empty p2pfile.");
        }
    }

    //created the filepieces data maps and initializes a new one to 0 if no file at all
    private void initializeFilePieces() throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            createEmptyFile(file);
        }
        try (FileInputStream fileInputStream = new FileInputStream(filePath)) {
            int index = 0;
            byte[] temp = new byte[(int)pieceSize];

            while (fileInputStream.read(temp) != -1) {
                pieces.put(index, temp);
                pieceAvailability.set(index);
                temp = new byte[(int)pieceSize];
                index++;
            }
        } catch (IOException e) {
            throw new IOException("Error reading p2pfile data.");
        }
    }

    //writes a piece to the file
    public synchronized void writePiece(int pieceIndex, byte[] pieceData) throws IOException {
        int offset = pieceIndex * (int)pieceSize;
        try (RandomAccessFile file = new RandomAccessFile(filePath, "rw")) {
            file.seek(offset);
            file.write(pieceData);
            pieces.put(pieceIndex, pieceData);
            pieceAvailability.set(pieceIndex);
        } catch (IOException e) {
            throw new IOException("Error writing p2pfile data.");
        }
    }


    public byte[] getPiece(int pieceIndex) {
        byte[] piece = pieces.get(pieceIndex);
        if (piece == null) {
            System.out.println("Piece " + pieceIndex + " was requested from this machine but we do not have it. This should not happen");
            return Util.intToFourBytes(pieceIndex); // Returning just the piece index
        }
        byte[] fullMessage = new byte[4 + piece.length];
        byte[] byteIndex = Util.intToFourBytes(pieceIndex);
        System.arraycopy(byteIndex, 0, fullMessage, 0, 4);
        System.arraycopy(piece, 0, fullMessage, 4, 4);
        return fullMessage;
    }

    public boolean hasPiece(int pieceIndex) {
        return pieceAvailability.get(pieceIndex);
    }

}
