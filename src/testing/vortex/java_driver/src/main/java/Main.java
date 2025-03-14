import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.Map;
import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;

/**
 * A Vortex driver using the Java language client for TigerBeetle.
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException(
          "java driver requires two positional command-line arguments");
    }

    byte[] clusterID = UInt128.asBytes(Long.parseLong(args[0]));

    var replicaAddressesArg = args[1];
    String[] replicaAddresses = replicaAddressesArg.split(",");
    if (replicaAddresses.length == 0) {
      throw new IllegalArgumentException(
          "REPLICAS must list at least one address (comma-separated)");
    }

    try (var client = new Client(clusterID, replicaAddresses)) {
      var reader = new Driver.Reader(Channels.newChannel(System.in));
      var writer = new Driver.Writer(Channels.newChannel(System.out));
      var driver = new Driver(client, reader, writer);
      while (true) {
        driver.next();
      }
    }
  }
}

record Driver(Client client, Reader reader, Writer writer) {
  static ByteOrder BYTE_ORDER = ByteOrder.nativeOrder();
  static {
    // We require little-endian architectures everywhere for efficient network
    // deserialization:
    if (BYTE_ORDER != ByteOrder.LITTLE_ENDIAN) {
      throw new RuntimeException("Native byte order LITTLE_ENDIAN expected");
    }
  }

  /**
   * Reads the next operation from stdin, runs it, collects the results, and writes them back to
   * stdout.
   */
  void next() throws IOException {
    reader.read(1 + 4); // operation + count
    var operation = Operation.fromValue(reader.u8());
    var count = reader.u32();

    switch (operation) {
      case CREATE_ACCOUNTS:
        createAccounts(reader, writer, count);
        break;
      case CREATE_TRANSFERS:
        createTransfers(reader, writer, count);
        break;
      case LOOKUP_ACCOUNTS:
        lookupAccounts(reader, writer, count);
        break;
      case LOOKUP_TRANSFERS:
        lookupTransfers(reader, writer, count);
        break;
      case GET_ACCOUNT_BALANCES:
      case GET_ACCOUNT_TRANSFERS:
      case QUERY_ACCOUNTS:
      case QUERY_TRANSFERS:
        // The Vortex workload currently does not request these operations, so this driver doesn't
        // support them (yet).
        throw new RuntimeException("unsupported operation: " + operation.name());
    }
  }

  void createAccounts(Reader reader, Writer writer, int count) throws IOException {
    reader.read(Driver.Operation.CREATE_ACCOUNTS.eventSize() * count);
    var batch = new AccountBatch(count);
    for (int index = 0; index < count; index++) {
      batch.add();
      batch.setId(reader.u128());
      reader.u128(); // `debits_pending`
      reader.u128(); // `debits_posted`
      reader.u128(); // `credits_pending`
      reader.u128(); // `credits_posted`
      batch.setUserData128(reader.u128());
      batch.setUserData64(reader.u64());
      batch.setUserData32(reader.u32());
      reader.u32(); // `reserved`
      batch.setLedger(reader.u32());
      batch.setCode(reader.u16());
      batch.setFlags(reader.u16());  
      reader.u64(); // `timestamp`
    }
    var results = client.createAccounts(batch);
    writer.allocate(4 + Driver.Operation.CREATE_ACCOUNTS.resultSize() * results.getLength());
    writer.u32(results.getLength());
    while (results.next()) {
      writer.u32(results.getIndex());
      writer.u32(results.getResult().value);
    }
    writer.flush();
  }

  void createTransfers(Reader reader, Writer writer, int count) throws IOException {
    reader.read(Driver.Operation.CREATE_TRANSFERS.eventSize() * count);
    var batch = new TransferBatch(count);
    for (int index = 0; index < count; index++) {
      batch.add();
      batch.setId(reader.u128());
      batch.setDebitAccountId(reader.u128());
      batch.setCreditAccountId(reader.u128());
      batch.setAmount(reader.u64(), reader.u64());
      batch.setPendingId(reader.u128());
      batch.setUserData128(reader.u128());
      batch.setUserData64(reader.u64());
      batch.setUserData32(reader.u32());
      batch.setTimeout(reader.u32());
      batch.setLedger(reader.u32());
      batch.setCode(reader.u16());
      batch.setFlags(reader.u16());  
      batch.setTimestamp(reader.u64());  
    }
    var results = client.createTransfers(batch);
    writer.allocate(4 + Driver.Operation.CREATE_ACCOUNTS.resultSize() * results.getLength());
    writer.u32(results.getLength());
    while (results.next()) {
      writer.u32(results.getIndex());
      writer.u32(results.getResult().value);
    }
    writer.flush();
  }

  void lookupAccounts(Reader reader, Writer writer, int count) throws IOException {
    reader.read(Driver.Operation.LOOKUP_ACCOUNTS.eventSize() * count);
    var batch = new IdBatch(count);
    for (int index = 0; index < count; index++) {
      batch.add();
      batch.setId(reader.u128());
    }
    var results = client.lookupAccounts(batch);
    writer.allocate(4 + Driver.Operation.LOOKUP_ACCOUNTS.resultSize() * results.getLength());
    writer.u32(results.getLength());
    while (results.next()) {
      writer.u128(results.getId());
      writer.u128(UInt128.asBytes(results.getDebitsPending()));
      writer.u128(UInt128.asBytes(results.getDebitsPosted()));
      writer.u128(UInt128.asBytes(results.getCreditsPending()));
      writer.u128(UInt128.asBytes(results.getCreditsPosted()));
      writer.u128(results.getUserData128());
      writer.u64(results.getUserData64());
      writer.u32(results.getUserData32());
      writer.u32(0); // `reserved`
      writer.u32(results.getLedger());
      writer.u16(results.getCode());
      writer.u16(results.getFlags());
      writer.u64(results.getTimestamp());
    }
    writer.flush();
  }

  void lookupTransfers(Reader reader, Writer writer, int count) throws IOException {
    reader.read(Driver.Operation.LOOKUP_TRANSFERS.eventSize() * count);
    var batch = new IdBatch(count);
    for (int index = 0; index < count; index++) {
      batch.add();
      batch.setId(reader.u128());
    }
    var results = client.lookupTransfers(batch);
    writer.allocate(4 + Driver.Operation.LOOKUP_TRANSFERS.resultSize() * results.getLength());
    writer.u32(results.getLength());
    while (results.next()) {
      writer.u128(results.getId());
      writer.u128(results.getDebitAccountId());
      writer.u128(results.getCreditAccountId());
      writer.u128(UInt128.asBytes(results.getAmount()));
      writer.u128(results.getPendingId());
      writer.u128(results.getUserData128());
      writer.u64(results.getUserData64());
      writer.u32(results.getUserData32());
      writer.u32(results.getTimeout());
      writer.u32(results.getLedger());
      writer.u16(results.getCode());
      writer.u16(results.getFlags());
      writer.u64(results.getTimestamp());
    }
    writer.flush();
  }

  // Based off `Operation` in `src/state_machine.zig`.
  enum Operation {
    CREATE_ACCOUNTS(129),
    CREATE_TRANSFERS(130),
    LOOKUP_ACCOUNTS(131),
    LOOKUP_TRANSFERS(132),
    GET_ACCOUNT_TRANSFERS(133),
    GET_ACCOUNT_BALANCES(134),
    QUERY_ACCOUNTS(135),
    QUERY_TRANSFERS(136);

    int value;

    Operation(int value) {
      this.value = value;
    }

    static Map<Integer, Operation> BY_VALUE = new HashMap<>();
    static {
      for (var element : values()) {
        BY_VALUE.put(element.value, element);
      }
    }

    static Operation fromValue(int value) {
      var result = BY_VALUE.get(value);
      if (result == null) {
        throw new RuntimeException("invalid operation: " + value);
      }
      return result;
    }

    int eventSize() {
      switch (this) {
      case CREATE_ACCOUNTS:
        return 128;
      case CREATE_TRANSFERS:
        return 128;
      case LOOKUP_ACCOUNTS:
        return 16;
      case LOOKUP_TRANSFERS:
        return 16;
      case GET_ACCOUNT_BALANCES:
      case GET_ACCOUNT_TRANSFERS:
      case QUERY_ACCOUNTS:
      case QUERY_TRANSFERS:
      default:
        throw new RuntimeException("unsupported operation: " + name());
      }
    }

    int resultSize() {
      switch (this) {
      case CREATE_ACCOUNTS:
        return 8;
      case CREATE_TRANSFERS:
        return 8;
      case LOOKUP_ACCOUNTS:
        return 128;
      case LOOKUP_TRANSFERS:
        return 128;
      case GET_ACCOUNT_BALANCES:
      case GET_ACCOUNT_TRANSFERS:
      case QUERY_ACCOUNTS:
      case QUERY_TRANSFERS:
      default:
        throw new RuntimeException("unsupported operation: " + name());
      }
    }
  }

  /**
   * Reads sized chunks into a buffer, and uses that to convert from
   * the Vortex driver binary protocol data to natively typed values. 
   *
   * The entire `read` buffer must be consumed before calling `read` again.
   */
  static class Reader {
    ReadableByteChannel input;
    ByteBuffer buffer = null;

    Reader(ReadableByteChannel input) {
      this.input = input;
    }

    void read(int count) throws IOException {
      if (this.buffer != null && this.buffer.hasRemaining()) {
        throw new RuntimeException("existing read buffer has %d bytes remaining"
            .formatted(this.buffer.remaining()));
      }
      this.buffer = ByteBuffer.allocateDirect(count).order(BYTE_ORDER);
      int read = 0;
      while (read < count) {
        read += input.read(this.buffer);
      }
      this.buffer.rewind();
    }

    int u8() throws IOException {
      return Byte.toUnsignedInt(buffer.get());
    }

    int u16() throws IOException {
      return Short.toUnsignedInt(buffer.getShort());
    }


    int u32() throws IOException {
      return (int) Integer.toUnsignedLong(buffer.getInt());
    }

    long u64() throws IOException {
      return buffer.getLong();
    }

    byte[] u128() throws IOException {
      var result = new byte[16];
      buffer.get(result, 0, 16);
      return result;
    }
  }

  /**
   * Allocates a buffer of a certain size, and writes natively typed values as 
   * Vortex driver binary protocol data.
   *
   * The entire allocated buffer must be filled before writing or allocating a
   * new buffer.
   */
  static class Writer {
    WritableByteChannel output;
    ByteBuffer buffer = null;

    Writer (WritableByteChannel output) {
      this.output = output;
    }

    void allocate(int size) {
      if (this.buffer != null && this.buffer.hasRemaining()) {
        throw new RuntimeException("existing buffer has %d bytes remaining"
            .formatted(this.buffer.remaining()));
      }
      this.buffer = ByteBuffer.allocateDirect(size).order(BYTE_ORDER).position(0);
    }

    /**
     * Writes the buffer to the output channel. The buffer must be filled.
     */
    void flush() throws IOException {
      if (this.buffer != null && this.buffer.hasRemaining()) {
        throw new RuntimeException("buffer has %d bytes remaining, refusing to write"
            .formatted(this.buffer.remaining()));
      }
      buffer.rewind();
      while (buffer.hasRemaining()) {
        output.write(buffer);
      }
    }

    void u8(int value) throws IOException {
      buffer.put((byte)value);
    }

    void u16(int value) throws IOException {
      buffer.putShort((short)value);
    }

    void u32(int value) throws IOException {
      buffer.putInt(value);
    }

    void u64(long value) throws IOException {
      buffer.putLong(value);
    }

    void u128(byte[] value) throws IOException {
      buffer.put(value);
    }

  }
}
