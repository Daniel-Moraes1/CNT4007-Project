package src;

public class NeighborInfo {

        int id;
        String name;
        int port;
        int hasFileFlag;

        public NeighborInfo(int id, String address, int port, int hasFileFlag){
            this.id = id;
            this.name = address;
            this.port = port;
            this.hasFileFlag = hasFileFlag;
        }
}
