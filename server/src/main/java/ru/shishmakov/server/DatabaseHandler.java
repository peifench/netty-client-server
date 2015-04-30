package ru.shishmakov.server;

import com.google.gson.Gson;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.CharsetUtil;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.shishmakov.entity.Client;
import ru.shishmakov.entity.Protocol;
import ru.shishmakov.helper.Database;
import ru.shishmakov.helper.DatabaseWorker;
import ru.shishmakov.helper.ResponseUtil;
import ru.shishmakov.helper.ResponseWorker;

import java.lang.invoke.MethodHandles;

/**
 * Class processes the HTTP Request which was sent to the server.
 * It is seeking the number of received messages in database. Protocol  "ping".
 *
 * @author Dmitriy Shishmakov
 * @see ChannelPipelineInitializer
 */
public class DatabaseHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles
            .lookup().lookupClass());
    /**
     * Protocol to receive the messages
     */
    private static final String PING = "ping";
    /**
     * Protocol to send the messages
     */
    private static final String PONG = "pong";
    /**
     * Converter Java Object -> JSON, JSON -> Java Object
     */
    private final Gson gson = new Gson();

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        logger.error("Fail at handler: " + cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    /**
     * Method to processes a {@code "ping"} message. Part of pipeline works with database.
     *
     * @param ctx instance to interact with {@link ChannelPipeline} and other handlers
     * @param msg the message to handle
     * @throws Exception is thrown if an error occurred
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (!(msg instanceof DatabaseWorker)) {
            return;
        }
        final DatabaseWorker worker = (DatabaseWorker) msg;
        final FullHttpRequest request = worker.getWorker();
        final Protocol protocol = buildFromJson(request);
        if (!PING.equalsIgnoreCase(protocol.getAction())) {
            final FullHttpResponse response = ResponseUtil.buildResponseHttp400(gson, ctx, "protocol");
            ctx.fireChannelRead(new ResponseWorker(response));
            return;
        }

        final Client client = findClient(protocol);
        final long quantity = client.getQuantity();
        final FullHttpResponse response = ResponseUtil.buildResponseHttp200(gson, ctx, PONG, PONG + " " + quantity);
        // pushed to the next channel
        ctx.fireChannelRead(new ResponseWorker(response));
    }

    /**
     * The main method of the server <i>"Ping Pong"</i>.
     * Client ID is a main opportunity for server to know all clients: new and old.
     * The type is an {@link ObjectId} defines unique of document into Mongo DB.
     * <p>
     * <b>Example of JSON document: </b><br/>
     * {@code {"_id" : ObjectId("552fcaadcebf0f9b1ae94ca4") , "quantity" : 2}}
     * <p>
     * <b>Example of FindAndModify query: </b> <br/>
     * {@code
     * {query: {"_id" : ObjectId("552fcaadcebf0f9b1ae94ca4")} ,
     * sort: {"_id" : 1},
     * update: {$inc: {"quantity" : 1}},
     * new: true, upset: true}}
     *
     * @param protocol instance of {@link Protocol}
     * @return quantity of requests from current client
     */
    private Client findClient(final Protocol protocol) {
        final DBCollection collection = Database.getDBCollection();
        if (collection == null) {
            throw new IllegalArgumentException("The database don't have a link with collection for making the query.");
        }

        final ObjectId id = protocol.getClientId();
        if (id == null) {
            // create a inserting query
            final BasicDBObject query = new BasicDBObject("quantity", 1);
            collection.insert(query);
            String json = JSON.serialize(query);
            return gson.fromJson(json, Client.class);
        }


        // create a finding query
        final BasicDBObject query = new BasicDBObject("_id", id);
        // create an ascending query
        final DBObject sortQuery = new BasicDBObject("_id", 1);
        // create an increment query
        final DBObject updateQuery = new BasicDBObject("$inc", new BasicDBObject("quantity", 1));
        // remove the document specified in the query
        final boolean remove = false;
        // return the modified document rather than the original
        final boolean returnNew = true;
        // create a new document if no document matches the query
        final boolean upsert = true;
        // subset of fields to return
        final DBObject fields = null;
        final DBObject dbObject = collection.findAndModify(query, fields, sortQuery, remove, updateQuery, returnNew, upsert);

        final String json = JSON.serialize(dbObject);
        return gson.fromJson(json, Client.class);
    }

    private Protocol buildFromJson(final FullHttpRequest request) {
        try {
            final String data = request.content().toString(CharsetUtil.UTF_8);
            final Protocol protocol = gson.fromJson(data, Protocol.class);
            return protocol == null ? new Protocol(null) : protocol;
        } catch (Exception e) {
            //can't parse: temp solution
            return new Protocol(null);
        }
    }
}