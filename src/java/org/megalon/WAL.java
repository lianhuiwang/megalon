package org.megalon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.megalon.avro.AvroWALEntry;

/**
 * An interface to the write-ahead log in HBase. TODO fine grained
 * locking/pooling instead of synchronized methods
 */
public class WAL {
	// TODO go fully asynchronous (use asynchbase?)

	public static final byte[] ENTRY_BYTES = "entry".getBytes();
	public static final byte[] N_BYTES = "n".getBytes();
	public static final int MAX_ACCEPT_TRIES = 5;
	public static final int MAX_PREPARE_TRIES = 5;

	// TODO don't hardcode
	byte[] cf = "CommitLogCF".getBytes();

	Log logger = LogFactory.getLog(WAL.class);
	final DatumReader<AvroWALEntry> reader = new SpecificDatumReader<AvroWALEntry>(
			AvroWALEntry.class);
	final DatumWriter<AvroWALEntry> writer = new SpecificDatumWriter<AvroWALEntry>(
			AvroWALEntry.class);
	Configuration hconf;
	HTable htable;
	Megalon megalon;

	public WAL(Megalon megalon) {
		this.megalon = megalon;
		connect();
	}

	public void connect() {
		hconf = HBaseConfiguration.create(); // TODO HBase address
		// hconf.set("hbase.zookeeper.quorum", commaSepZkServers);
		try {
			htable = new HTable(hconf, "CommitLog");
		} catch (IOException e) {
			logger.warn("HTable connection failure", e);
		}
	}

	void requireHtableConnected() throws IOException {
		if (htable == null) {
			throw new IOException("HTable disconnected");
		}
	}

	synchronized public WALEntry read(byte[] entityGroup, long walIndex) 
	throws IOException {
		requireHtableConnected();
		byte[] row = ArrayUtils.addAll(entityGroup, Util.longToBytes(walIndex));
		Get get = new Get(row);
		get.addColumn(cf, ENTRY_BYTES);

		Result result = htable.get(get);
		KeyValue columnKv = result.getColumnLatest(cf, ENTRY_BYTES);

		if (columnKv != null) {
			byte[] valBytes = columnKv.getValue();
			Decoder decoder = DecoderFactory.get()
					.binaryDecoder(valBytes, null);
			AvroWALEntry prevEntry = reader.read(null, decoder);
			return new WALEntry(prevEntry);
		} else {
			return null;
		}
	}

	/**
	 * Looks at the WAL in HBase to get the most recent data for a certain WAL
	 * entry, identified by the proposal number. If there was a previously
	 * accepted value for this log entry, it would be returned, and nothing will
	 * be written. If there was a previously "promised" n, and the caller's n is
	 * greater, then the caller's n will be
	 * 
	 * @param n
	 * @return null: Paxos ack of prepare message. Non-null WALEntry: the
	 *         existing log message, which also means Paxos NACK.
	 * @throws IOException
	 */
	synchronized public WALEntry prepareLocal(byte[] entityGroup, long walIndex,
	long n) throws IOException {
		requireHtableConnected();
		int tries = 0;
		while (true) {
			try {
				WALEntry existingEntry = read(entityGroup, walIndex);
				// Read the preexisting log entry, see if we should supersede it
				if (existingEntry != null) {
					if (!(existingEntry.status == WALEntry.Status.PREPARED && existingEntry.n < n)) {
						return existingEntry;
					}
				}

				// We haven't seen anything yet for this WAL entry
				WALEntry newEntry = new WALEntry(n, null,
						WALEntry.Status.PREPARED);

				// CAS: require that no one wrote since we read.
				putWAL(entityGroup, walIndex, newEntry, true, null);
				return null;
			} catch (CasChanged e) {
				tries++;
				if (tries >= MAX_PREPARE_TRIES) {
					logger.debug("acceptLocal too many CAS retries");
					throw new TooConcurrent();
				}
				logger.debug("acceptLocal CAS failed, retrying");
			}
		}
	}

	/**
	 * Like {@link #putWAL(long, WALEntry, WALEntry)}, but writes blindly
	 * without doing a compare-and-swap check.
	 */
	synchronized protected void putWAL(byte[] entityGroup, long walIndex, 
	WALEntry entry) throws IOException {
		putWAL(entityGroup, walIndex, entry, false, null);
	}

	/**
	 * Write an entry to the WAL in HBase. If doCasCheck is true, this does a
	 * compare-and-swap so that the database will only change if the existing
	 * entry's entry.n is equal to casCheckEntry.n .
	 * 
	 * @param casCheckEntry
	 *            The write won't occur unless the entry.n in the database is
	 *            equal to casCheckEntry.n . Pass "null" to require that the
	 *            database value not exist.
	 */
	synchronized protected void putWAL(byte[] entityGroup, long walIndex, 
	WALEntry entry, boolean doCasCheck, WALEntry casCheckEntry) throws IOException {
		requireHtableConnected();

		ByteArrayOutputStream bao = new ByteArrayOutputStream();
		BinaryEncoder e = EncoderFactory.get().binaryEncoder(bao, null);
		
		writer.write(entry.toAvro(), e);
		e.flush();
		byte[] serializedWALEntry = bao.toByteArray();

		byte[] row = ArrayUtils.addAll(entityGroup, Util.longToBytes(walIndex)); 
		Put put = new Put(row);
		put.add(cf, ENTRY_BYTES, serializedWALEntry);
		put.add(cf, N_BYTES, Util.longToBytes(entry.n));
		if (doCasCheck) {
			// Atomic compare and swap: write the new value if and only if the
			// n value hasn't changed since we read it.
			byte[] casNBytes;
			if (casCheckEntry == null) {
				casNBytes = null;
			} else {
				casNBytes = Util.longToBytes(casCheckEntry.n);
			}
			if (!htable.checkAndPut(row, cf, N_BYTES, casNBytes, put)) {
				throw new CasChanged();
			}
		} else {
			htable.put(put);
		}
	}

	/**
	 * Attempt a Paxos accept on behalf of the local data center.
	 * 
	 * @return true if the entry had the highest n seen yet and was written to
	 *         the WAL. false otherwise.
	 */
	synchronized protected boolean acceptLocal(byte[] entityGroup, long walIndex,
	WALEntry entry) throws IOException {
		requireHtableConnected();

		int tries = 0;
		while (true) {
			try {
				WALEntry existingEntry = read(entityGroup, walIndex);
				if (existingEntry != null && existingEntry.n > entry.n) {
					return false;
				} else {
					putWAL(entityGroup, walIndex, entry, true, existingEntry);
					return true;
				}
			} catch (CasChanged e) {
				tries++;
				if (tries >= MAX_ACCEPT_TRIES) {
					logger.debug("acceptLocal too many CAS retries");
					throw new TooConcurrent();
				}
				logger.debug("acceptLocal CAS failed, retrying");
			}
		}
	}
}
