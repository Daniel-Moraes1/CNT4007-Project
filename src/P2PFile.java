package src;
import java.io.*;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

public class P2PFile {

    private final String filePath;
    private final long pieceSize;
    private final long fileSize;
    private final Map<Integer, byte[]> pieces;
    private final BitSet pieceAvailability;

    public P2PFile(String filePath, long fileSize, long pieceSize) throws IOException {
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;
        this.pieces = new HashMap<>();
        this.pieceAvailability = new BitSet();
        initializeFilePieces();
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
        return pieces.get(pieceIndex);
    }

    public boolean hasPiece(int pieceIndex) {
        return pieceAvailability.get(pieceIndex);
    }

}
