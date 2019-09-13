package main.java.zoomeditor.service;

import main.java.ZoomFirmwareEditor;
import main.java.zoomeditor.model.Effect;
import main.java.zoomeditor.model.FileTable;
import main.java.zoomeditor.model.Firmware;
import main.java.zoomeditor.util.ArrayUtils;
import main.java.zoomeditor.util.ByteUtils;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

class FileTableService {
    private static volatile FileTableService instance = null;
    private static final Logger log = Logger.getLogger(FileTableService.class.getName());

    private FileTableService() {
    }

    static FileTableService getInstance() {
        if (instance == null) {
            synchronized (FileTableService.class) {
                if (instance == null) {
                    instance = new FileTableService();
                }
            }
        }
        return instance;
    }

    /**
     * Fills the firmware's file table.
     *
     * @param firm firmware
     * @return true, if successfully filled
     */
    boolean fillFileTable(Firmware firm) {
        FileTable fileTable = new FileTable();
        fileTable.setFileTablePosition(-1);
        int possiblePosition = -1;
        for (int i = 0; i < 4; i++) {
            int position = Firmware.BLOCK_SIZE * (3 + i * 2);

            if (isValidFileTablePosition(firm, position)) {
                // Assume that true file table is that one, which 5th byte is "FF"
                // (other possible solutions: table with MAX first byte; table with MAX third byte).
                if (firm.getSystemBytes()[position + 4] == (byte) 0xFF) {
                    // main table position
                    log.info("Main file table (0-3): " + i + ", position: " + position);
                    fileTable.setFileTablePosition(position);
                } else {
                    // other valid table position
                    possiblePosition = position;
                }
            }
        }

        // if main position is not found, then set last possible position
        if (fileTable.getFileTablePosition() == -1) {
            if (possiblePosition == -1) {
                return false;
            } else {
                fileTable.setFileTablePosition(possiblePosition);
            }
        }

        firm.setFileTable(fileTable);
        return true;
    }

    /**
     * Validates the position of file table.
     *
     * @param firm     firmware
     * @param position position
     * @return true, if position is valid
     */
    private boolean isValidFileTablePosition(Firmware firm, int position) {
        return firm.getSystemBytes()[position + 1] == (byte) 0xA5 && firm.getSystemBytes()[position + 5] == (byte) 0xFF;
    }

    /**
     * Fills the effects list and blocks array using firmware's file table.
     *
     * @param firm firmware
     */
    void fillEffectsAndBlocks(Firmware firm) {
        // prepare block allocation array
        String[] blocks = new String[firm.getBinBlocksCount() - Firmware.FIRST_DATA_BLOCK];
        blocks[0] = "RESERVED (part of file table)";
        firm.setBlocks(blocks);
        firm.setEffects(new ArrayList<>());

        // fill effects list and blocks array
        for (int itemPointer = firm.getFileTable().getFileTablePosition() + FileTable.SYSTEM_DATA_SIZE;
             itemPointer < firm.getFileTable().getFileTablePosition() + Firmware.BLOCK_SIZE * 2;
             itemPointer = itemPointer + FileTable.ITEM_SIZE) {
            //log.info("*** Effect pointer: " + itemPointer + " ***");

            Effect effect = EffectService.makeEffectFromFileTableItem(ArrayUtils.copyPart(firm.getSystemBytes(),
                    itemPointer, FileTable.ITEM_SIZE));
            if (effect != null && effect.getFileName() != null && !effect.getFileName().isEmpty()) {
                if ("true".equalsIgnoreCase(ZoomFirmwareEditor.getProperty("excludeSequenceFiles"))
                        && Firmware.EXCLUDE_FILENAMES.contains(effect.getFileName())) {
                    continue;
                }

                try {
                    boolean isSuccess = fillEffectContent(firm, effect);
                    if (!isSuccess) {
                        FileTableService.getInstance().clearBlocksFromFilename(firm, effect.getFileName());
                    }
                    effect.setName(effect.extractNameFromContent());
                    effect.setType(effect.extractTypeFromContent());
                    //log.info(effect.getInfo());
                } catch (Exception e) {
                    log.log(Level.SEVERE, effect.getFileName() + " content getting error: " + e.getMessage(), e);
                }

                if (effect.getContent() != null
                        && !FirmwareService.getInstance().getEffectNames(firm).contains(effect.getFileName())) {
                    //log.info(effect.getFileName() + " has been added to the list.");
                    firm.getEffects().add(effect);
                }
            }
        }
    }

    /**
     * Fills the effect content with bytes extracted from firmware.
     * At the same time fills the firmware's blocks table with effect name.
     *
     * @param firm  firmware
     * @param effect effect, which content should be filled
     * @return true, if content is successfully filled
     */
    private boolean fillEffectContent(Firmware firm, Effect effect) {
        byte[] content = new byte[effect.getSize()];
        int emptyAddress = ByteUtils.bytesToUnsignedShortAsInt(ByteUtils.hexStringToByteArray("FFFF"));
        int previousAddress = emptyAddress;
        int address = effect.getAddress();
        int blockStartPos = Firmware.BLOCK_SIZE * address;
        firm.getBlocks()[address] = effect.getFileName();
        int currentSize = 0;

        while (true) {
            int dataSize = ByteUtils.bytesToUnsignedShortAsInt(ArrayUtils.copyPart(firm.getDataBytes(),
                    blockStartPos + Firmware.BLOCK_SIZE_OFFSET, Firmware.BLOCK_SIZE_SIZE));
            System.arraycopy(firm.getDataBytes(), blockStartPos + Firmware.BLOCK_INFO_SIZE,
                    content, currentSize, dataSize);
            currentSize = currentSize + dataSize;

            // validate block's previous address
            if (previousAddress != emptyAddress) {
                byte[] previousAddressBytes = ArrayUtils.copyPart(firm.getDataBytes(),
                        blockStartPos + Firmware.BLOCK_PREV_ADDR_OFFSET, Firmware.BLOCK_PREV_ADDR_SIZE);
                int blockPrevAddress = ByteUtils.bytesToUnsignedShortAsInt(previousAddressBytes);

                if (previousAddress != blockPrevAddress) {
                    log.severe("Address validation error, effect: " + effect.getFileName());
                    log.severe("current address: " + address);
                    log.severe("real previous address: " + previousAddress);
                    log.severe("block previous address: " + blockPrevAddress);
                    return false;
                }
            }

            previousAddress = address;
            byte[] nextAddressBytes = ArrayUtils.copyPart(firm.getDataBytes(),
                    blockStartPos + Firmware.BLOCK_NEXT_ADDR_OFFSET, Firmware.BLOCK_NEXT_ADDR_SIZE);
            address = ByteUtils.bytesToUnsignedShortAsInt(nextAddressBytes);
            if (address == emptyAddress) {
                // validate size
                if (currentSize != effect.getSize()) {
                    log.severe(effect.getFileName() + " has invalid size: " + currentSize + " VS " + effect.getSize());
                    return false;
                }
                break;
            }

            // next address exists
            firm.getBlocks()[address] = effect.getFileName();
            blockStartPos = Firmware.BLOCK_SIZE * address;
        }

        effect.setContent(content);
        return true;
    }

    /**
     * Rebuilds all file tables using effects list data.
     *
     * @param firm firmware
     */
    void rebuildAllFileTables(Firmware firm) {
        byte[] fileTableBytes = ArrayUtils.makeAndFillArray(2 * Firmware.BLOCK_SIZE, (byte) 0xFF);
        int itemPointer = FileTable.SYSTEM_DATA_SIZE;
        for (Effect effect : firm.getEffects()) {
            System.arraycopy(effect.getFileTableItem(), 0, fileTableBytes, itemPointer, FileTable.ITEM_SIZE);
            itemPointer = itemPointer + FileTable.ITEM_SIZE;
        }

        for (int i = 0; i < 4; i++) {
            int position = Firmware.BLOCK_SIZE * (3 + i * 2);
            if (FileTableService.getInstance().isValidFileTablePosition(firm, position)) {
                log.info("Updating file table nr: " + i + ", position: " + position);
                // insert new table, but save first 8 bytes
                System.arraycopy(fileTableBytes, FileTable.SYSTEM_DATA_SIZE, firm.getSystemBytes(),
                        position + FileTable.SYSTEM_DATA_SIZE,
                        fileTableBytes.length - FileTable.SYSTEM_DATA_SIZE);
            }
        }
    }

    /**
     * Clears firmware's blocks array from given effect name.
     *
     * @param firm     firmware
     * @param fileName filename to remove from blocks
     */
    void clearBlocksFromFilename(Firmware firm, String fileName) {
        if (fileName != null) {
            for (int i = 1; i < firm.getBlocks().length; i++) { // NB! start from 1, blocks[0] is reserved
                if (fileName.equals(firm.getBlocks()[i])) {
                    firm.getBlocks()[i] = null;
                }
            }
        }
    }

}
