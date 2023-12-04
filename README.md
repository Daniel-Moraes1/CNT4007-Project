
# CNT4007 Project


P2P network implementation with Java

Group Number 58 

## Authors

- [@Daniel Moraes](https://github.com/Daniel-Moraes1)
- [@Alexander Decious](https://github.com/adeci)
- [@Ryan Houston](https://github.com/ryanh6900)

## Contributions By Member

- Daniel:
    - Project planning and development
    - Setting up connections between peers on startup
    - Handshake
    - Message sending
    - Message handling
    - Shutdown functionality
    - Deadlock debugging
    - Thread development
    
- Alexander: Makefile, Logging, Writing to file, Deadlock debugging
- Ryan:
  - Assisted with the debugging of the deadlocking in the initiator function. 
  - Helped during the design work in the early stages of the project. 
  - Made minor changes throughout.
  - Setup the CISE machine tests

## Demo link
https://youtu.be/gOgdwAfxAfA

## Running

To compile the binaries and generate executable P2PNet.jar file

```bash
  make
```

To cleanly remove compiled files run
```bash
  make clean
```

To execute run
```bash
  java -jar P2PNet.jar {peer id goes here}
```


## Network Configuration

Copy an alternative configuration into the main Config folder

| Config Preset | Transfer File     | Description                |
| :-------- | :------- | :------------------------- |
| project_config_file_small | thefile | **1.76MB** file initially only on peers 1001 and 1006|
| project_config_file_large | tree.jpg | **23.1MB** png file initially only on peer 1001|

#### Peers must be initialized to the network in ascending order

## Accomplishments
We were able to accomplish all of the requirements in the project spec including all of the functionality for communicating between neighbors.

## Project overview
This is an implementation of a Peer to Peer network.
One or more peers start with the file and work to distribute them amongst other peers. Peers are able to request pieces of the file which they do not have from other peers that do have them until all peers have fully downloaded the file. This implements the following 9 message protocols:

## Protocol Implementations
In our implementation, each peer maintains 2 + 2n threads where n is the number of neighbors the peer has in the network. These include one thread for welcoming new peers, one thread for maintaing a timer for choking and unchoking intervals, and two threads per peer where one thread (initiator) sends requests to other peers for pieces it does not have, and the other thread (responder) responds to incoming messages from neighbors to properly address them. Each peer maintains all of the pieces that each other neighbor has in order to track who can deliver the pieces it needs.

This implementation involves the following protocols: 

### Choking and Unchoking
Every peer maintains a count of how many pieces each other peer has requested from it in the interval since the last unchoking period which is counted upon receiving requests. We sort the neighbors who are interested in our pieces by the number of pieces they have requested and unchoke the neighbors who have requested the most pieces if they are currently choked. Previously unchoked neighbors who are not longer in the top requestors are now choked. Neighbors who tie in number of pieces are randomly assigned to be unchoked.

Additionally, one neighbor from those interested in us and is currently choked is selected to be optimistically unchoked.

### Interested and Not Interested
Each peer tracks the peers that it is interested in order to tell whether it should request pieces. When a neighbor receives a piece that another peer does not have, the peer will send an INTERESTED message to let the neighbor know that it would like to request that piece. Neighbors that do not have any pieces that their peers are interested in are sent Not Interested messages to let them know that they should not unchoke their peers.

### Have
Whenever a peer receives a piece, it sends HAVE messages to all of its neighbors and includes the index of the piece it has requested. This allows each peer to keep track of all the pieces that the other peers have.

### Request
Peers send REQUEST messages to their neighbors to request pieces that the neighbor has an which the peer sending the message does not. Pieces that each of a peer's neighbors have is maintained as a hashset to easily track pieces. Pieces to request from are always chosen randomly from this hash set.

### Piece
Peers send PIECE messages to their neighbors including the byte contents of the piece they are delivering as long as the neighbor who has requested the piece is unchoked. This is how the file gets distributed among the peers.

### Shutdown and Shutdown ACK
We use shutdown calls and shutdown ACKs to inform peers that it is time to shutdown when all of the peers in the network have downloaded the complete file and are ready to terminate the program.
