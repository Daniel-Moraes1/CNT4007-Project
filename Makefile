JAVAC = javac
JAR = jar
JFLAGS = -d bin
SRC_DIR = src
BIN_DIR = bin
JAR_NAME = P2PNet.jar
MAIN_CLASS = src.Peer

SOURCES := $(wildcard $(SRC_DIR)/*.java)
CLASSES := $(patsubst $(SRC_DIR)/%.java,$(BIN_DIR)/%.class,$(SOURCES))

all: $(CLASSES) jar

$(BIN_DIR)/%.class: $(SRC_DIR)/%.java
	@mkdir -p $(@D)
	$(JAVAC) $(JFLAGS) $<

jar: $(CLASSES)
	$(JAR) cfe $(JAR_NAME) $(MAIN_CLASS) -C $(BIN_DIR) .

clean:
	rm -rf $(BIN_DIR) $(JAR_NAME)

.PHONY: all clean
