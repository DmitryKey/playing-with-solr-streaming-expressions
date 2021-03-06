package com.o19s.solr.streaming;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient.Builder;
import org.apache.solr.client.solrj.io.SolrClientCache;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.comp.StreamComparator;
import org.apache.solr.client.solrj.io.stream.PushBackStream;
import org.apache.solr.client.solrj.io.stream.StreamContext;
import org.apache.solr.client.solrj.io.stream.TupleStream;
import org.apache.solr.client.solrj.io.stream.expr.Explanation;
import org.apache.solr.client.solrj.io.stream.expr.Expressible;
import org.apache.solr.client.solrj.io.stream.expr.StreamExplanation;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionNamedParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionValue;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;
import org.apache.solr.client.solrj.io.stream.expr.Explanation.ExpressionType;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BumpStream extends TupleStream implements Expressible {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	public static String BATCH_INDEXED_FIELD_NAME = "batchBumped"; // field name in summary tuple for #docs updated in
																	// batch
	private String collection;
	private String zkHost;
	private int updateBatchSize;
	private String bumpFieldName;
	private String idFieldName;
	private int batchNumber;
	private long totalDocsIndex;
	private PushBackStream tupleSource;
	private transient SolrClientCache cache;
	private transient CloudSolrClient cloudSolrClient;
	private List<SolrInputDocument> documentBatch = new ArrayList();
	private String coreName;

	public BumpStream(StreamExpression expression, StreamFactory factory) throws IOException {
		log.error("\n\nConfiguring BumpStream\n\n\n");
		
		String collectionName = factory.getValueOperand(expression, 0);
		verifyCollectionName(collectionName, expression);

		String zkHost = findZkHost(factory, collectionName, expression);
		verifyZkHost(zkHost, collectionName, expression);

		int updateBatchSize = extractBatchSize(expression, factory);
		bumpFieldName = extractBumpFieldName(expression, factory);
		idFieldName = extractIDFieldName(expression, factory);

		// Extract underlying TupleStream.
		List<StreamExpression> streamExpressions = factory.getExpressionOperandsRepresentingTypes(expression,
				Expressible.class, TupleStream.class);
		if (1 != streamExpressions.size()) {
			throw new IOException(
					String.format(Locale.ROOT, "Invalid expression %s - expecting a single stream but found %d",
							expression, streamExpressions.size()));
		}
		StreamExpression sourceStreamExpression = streamExpressions.get(0);

		init(collectionName, factory.constructStream(sourceStreamExpression), zkHost, updateBatchSize, bumpFieldName);
	}

	public BumpStream(String collectionName, TupleStream tupleSource, String zkHost, int updateBatchSize,
			String bumpFieldName) throws IOException {
		if (updateBatchSize <= 0) {
			throw new IOException(
					String.format(Locale.ROOT, "batchSize '%d' must be greater than 0.", updateBatchSize));
		}
		init(collectionName, tupleSource, zkHost, updateBatchSize, bumpFieldName);
	}

	private void init(String collectionName, TupleStream tupleSource, String zkHost, int updateBatchSize,
			String bumpFieldName) {
		this.collection = collectionName;
		this.zkHost = zkHost;
		this.updateBatchSize = updateBatchSize;
		this.bumpFieldName = bumpFieldName;
		this.tupleSource = new PushBackStream(tupleSource);
	}

	@Override
	public void open() throws IOException {
		setCloudSolrClient();
		tupleSource.open();
	}

	@Override
	public Tuple read() throws IOException {

		for (int i = 0; i < updateBatchSize; i++) {
			Tuple tuple = tupleSource.read();
			if (tuple.EOF) {
				if (documentBatch.isEmpty()) {
					return tuple;
				} else {
					tupleSource.pushBack(tuple);
					uploadBatchToCollection(documentBatch);
					int b = documentBatch.size();
					documentBatch.clear();
					return createBatchSummaryTuple(b);
				}
			}
			documentBatch.add(convertTupleToSolrDocument(tuple));
		}

		uploadBatchToCollection(documentBatch);
		int b = documentBatch.size();
		documentBatch.clear();
		return createBatchSummaryTuple(b);
	}

	@Override
	public void close() throws IOException {
		if (cache == null && cloudSolrClient != null) {
			cloudSolrClient.close();
		}
		tupleSource.close();
	}

	@Override
	public StreamComparator getStreamSort() {
		return tupleSource.getStreamSort();
	}

	@Override
	public List<TupleStream> children() {
		ArrayList<TupleStream> sourceList = new ArrayList<TupleStream>(1);
		sourceList.add(tupleSource);
		return sourceList;
	}

	@Override
	public StreamExpression toExpression(StreamFactory factory) throws IOException {
		return toExpression(factory, true);
	}

	private StreamExpression toExpression(StreamFactory factory, boolean includeStreams) throws IOException {
		StreamExpression expression = new StreamExpression(factory.getFunctionName(this.getClass()));
		expression.addParameter(collection);
		expression.addParameter(new StreamExpressionNamedParameter("zkHost", zkHost));
		expression.addParameter(new StreamExpressionNamedParameter("batchSize", Integer.toString(updateBatchSize)));

		if (includeStreams) {
			if (tupleSource instanceof Expressible) {
				expression.addParameter(((Expressible) tupleSource).toExpression(factory));
			} else {
				throw new IOException(
						"This ParallelStream contains a non-expressible TupleStream - it cannot be converted to an expression");
			}
		} else {
			expression.addParameter("<stream>");
		}

		return expression;
	}

	@Override
	public Explanation toExplanation(StreamFactory factory) throws IOException {

		// An update stream is backward wrt the order in the explanation. This stream is
		// the "child"
		// while the collection we're updating is the parent.

		StreamExplanation explanation = new StreamExplanation(getStreamNodeId() + "-datastore");

		explanation.setFunctionName(String.format(Locale.ROOT, "solr (%s)", collection));
		explanation.setImplementingClass("Solr/Lucene");
		explanation.setExpressionType(ExpressionType.DATASTORE);
		explanation.setExpression("Update into " + collection);

		// child is a datastore so add it at this point
		StreamExplanation child = new StreamExplanation(getStreamNodeId().toString());
		child.setFunctionName(String.format(Locale.ROOT, factory.getFunctionName(getClass())));
		child.setImplementingClass(getClass().getName());
		child.setExpressionType(ExpressionType.STREAM_DECORATOR);
		child.setExpression(toExpression(factory, false).toString());
		child.addChild(tupleSource.toExplanation(factory));

		explanation.addChild(child);

		return explanation;
	}

	@Override
	public void setStreamContext(StreamContext context) {
		this.cache = context.getSolrClientCache();
		this.coreName = (String) context.get("core");
		this.tupleSource.setStreamContext(context);
	}

	private void verifyCollectionName(String collectionName, StreamExpression expression) throws IOException {
		if (null == collectionName) {
			throw new IOException(String.format(Locale.ROOT,
					"invalid expression %s - collectionName expected as first operand", expression));
		}
	}

	private String findZkHost(StreamFactory factory, String collectionName, StreamExpression expression) {
		StreamExpressionNamedParameter zkHostExpression = factory.getNamedOperand(expression, "zkHost");
		if (null == zkHostExpression) {
			String zkHost = factory.getCollectionZkHost(collectionName);
			if (zkHost == null) {
				return factory.getDefaultZkHost();
			} else {
				return zkHost;
			}
		} else if (zkHostExpression.getParameter() instanceof StreamExpressionValue) {
			return ((StreamExpressionValue) zkHostExpression.getParameter()).getValue();
		}

		return null;
	}

	private void verifyZkHost(String zkHost, String collectionName, StreamExpression expression) throws IOException {
		if (null == zkHost) {
			throw new IOException(String.format(Locale.ROOT,
					"invalid expression %s - zkHost not found for collection '%s'", expression, collectionName));
		}
	}

	private String extractBumpFieldName(StreamExpression expression, StreamFactory factory) throws IOException {
		StreamExpressionNamedParameter bumpFieldNameParameter = factory.getNamedOperand(expression, "field");
		if (null == bumpFieldNameParameter) {
			return "bump";
		} else if (bumpFieldNameParameter.getParameter() instanceof StreamExpressionValue) {
			return ((StreamExpressionValue) bumpFieldNameParameter.getParameter()).getValue();
		}

		return null;
	}
	
	private String extractIDFieldName(StreamExpression expression, StreamFactory factory) throws IOException {
		StreamExpressionNamedParameter idFieldNameParameter = factory.getNamedOperand(expression, "id");
		if (null == idFieldNameParameter) {
			return "id";
		} else if (idFieldNameParameter.getParameter() instanceof StreamExpressionValue) {
			return ((StreamExpressionValue) idFieldNameParameter.getParameter()).getValue();
		}

		return null;
	}	

	private int extractBatchSize(StreamExpression expression, StreamFactory factory) throws IOException {
		StreamExpressionNamedParameter batchSizeParam = factory.getNamedOperand(expression, "batchSize");
		if (null == batchSizeParam || null == batchSizeParam.getParameter()
				|| !(batchSizeParam.getParameter() instanceof StreamExpressionValue)) {
			throw new IOException(String.format(Locale.ROOT,
					"Invalid expression %s - expecting a 'batchSize' parameter of type positive integer but didn't find one",
					expression));
		}

		String batchSizeStr = ((StreamExpressionValue) batchSizeParam.getParameter()).getValue();
		return parseBatchSize(batchSizeStr, expression);
	}

	private int parseBatchSize(String batchSizeStr, StreamExpression expression) throws IOException {
		try {
			int batchSize = Integer.parseInt(batchSizeStr);
			if (batchSize <= 0) {
				throw new IOException(String.format(Locale.ROOT,
						"invalid expression %s - batchSize '%d' must be greater than 0.", expression, batchSize));
			}
			return batchSize;
		} catch (NumberFormatException e) {
			throw new IOException(String.format(Locale.ROOT,
					"invalid expression %s - batchSize '%s' is not a valid integer.", expression, batchSizeStr));
		}
	}

	private void setCloudSolrClient() {
		if (this.cache != null) {
			this.cloudSolrClient = this.cache.getCloudSolrClient(zkHost);
		} else {
			final List<String> hosts = new ArrayList<>();
			hosts.add(zkHost);
			this.cloudSolrClient = new Builder(hosts, Optional.empty()).build();
			this.cloudSolrClient.connect();
		}
	}

	private SolrInputDocument convertTupleToSolrDocument(Tuple tuple) {
		SolrInputDocument doc = new SolrInputDocument();
		doc.addField( idFieldName, tuple.get(idFieldName));
		
		Map<String, Object> operation = new HashMap<>();
		operation.put("inc", 1);
		doc.addField(bumpFieldName, operation);
		
		log.error("Tuple [{}] was converted into SolrInputDocument [{}].", tuple, doc);

		return doc;
	}

	private void uploadBatchToCollection(List<SolrInputDocument> documentBatch) throws IOException {
		if (documentBatch.size() == 0) {
			return;
		}

		try {
			cloudSolrClient.add(collection, documentBatch);
		} catch (SolrServerException | IOException e) {
			log.warn("Unable to bump documents in collection due to unexpected error.", e);
			String className = e.getClass().getName();
			String message = e.getMessage();
			throw new IOException(String.format(Locale.ROOT,
					"Unexpected error when bumping documents in collection %s- %s:%s", collection, className, message));
		}
	}

	private Tuple createBatchSummaryTuple(int batchSize) {
		assert batchSize > 0;
		Map m = new HashMap();
		this.totalDocsIndex += batchSize;
		++batchNumber;
		m.put(BATCH_INDEXED_FIELD_NAME, batchSize);
		m.put("totalBumped", this.totalDocsIndex);
		m.put("batchNumber", batchNumber);
		if (coreName != null) {
			m.put("worker", coreName);
		}
		return new Tuple(m);
	}

}
