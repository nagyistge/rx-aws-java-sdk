package com.amazonaws.http;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;

import com.amazonaws.AmazonWebServiceResponse;
import com.amazonaws.ResponseMetadata;
import com.amazonaws.internal.CRC32MismatchException;
import com.amazonaws.transform.JsonUnmarshallerContext;
import com.amazonaws.transform.JsonRxNettyUnmarshallerContextImpl;
import com.amazonaws.transform.Unmarshaller;
import com.amazonaws.transform.VoidJsonUnmarshaller;
import com.amazonaws.util.CRC32ChecksumCalculatingInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import rx.Observable;
import rx.functions.*;
import io.netty.buffer.ByteBuf;
import iep.io.reactivex.netty.protocol.http.client.HttpClientResponse;

public class JsonRxNettyResponseHandler<T> implements RxNettyResponseHandler<AmazonWebServiceResponse<T>> {

    private Unmarshaller<T, JsonUnmarshallerContext> responseUnmarshaller;

    private static final Log log = LogFactory.getLog("com.amazonaws.request");

    private static JsonFactory jsonFactory = new JsonFactory();

    public boolean needsConnectionLeftOpen = false;

    public JsonRxNettyResponseHandler(Unmarshaller<T, JsonUnmarshallerContext> responseUnmarshaller) {
        this.responseUnmarshaller = responseUnmarshaller;

        if (this.responseUnmarshaller == null) {
            this.responseUnmarshaller = new VoidJsonUnmarshaller<T>();
        }
    }

    public AmazonWebServiceResponse<T> handle(HttpResponse response) throws Exception {
      throw new UnsupportedOperationException("apache http client not supported");
    }

    public Observable<AmazonWebServiceResponse<T>> handle(HttpClientResponse<ByteBuf> response) throws Exception {

      //return response.flatmap(r -> {
        log.trace("Parsing service response JSON");

        return response.getContent().reduce(
          new ByteArrayOutputStream(),
          (out, bb) -> {
            try {
              bb.readBytes(out, bb.readableBytes());
              return out;
            }
            catch (java.io.IOException e) {
              throw new RuntimeException(e);
            }
          })
        .map(out -> {
          return new ByteArrayInputStream(out.toByteArray());
        })
        .map(in -> {
          String CRC32Checksum = response.getHeaders().get("x-amz-crc32");
          CRC32ChecksumCalculatingInputStream crc32ChecksumInputStream = null;
          JsonParser jsonParser = null;
          try {
            if (!needsConnectionLeftOpen) {
              if (CRC32Checksum != null) {
                crc32ChecksumInputStream = new CRC32ChecksumCalculatingInputStream(in);
                jsonParser = jsonFactory.createJsonParser(crc32ChecksumInputStream);
              } else {
                jsonParser = jsonFactory.createJsonParser(in);
              }
            }

            AmazonWebServiceResponse<T> awsResponse = new AmazonWebServiceResponse<T>();
            JsonUnmarshallerContext unmarshallerContext = new JsonRxNettyUnmarshallerContextImpl(jsonParser, response.getHeaders());
            registerAdditionalMetadataExpressions(unmarshallerContext);

            T result = responseUnmarshaller.unmarshall(unmarshallerContext);

            if (CRC32Checksum != null) {
              long serverSideCRC = Long.parseLong(CRC32Checksum);
              long clientSideCRC = crc32ChecksumInputStream.getCRC32Checksum();
              if (clientSideCRC != serverSideCRC) {
                throw new CRC32MismatchException("Client calculated crc32 checksum didn't match that calculated by server side");
              }
            }

            awsResponse.setResult(result);

            Map<String, String> metadata = unmarshallerContext.getMetadata();
            metadata.put(ResponseMetadata.AWS_REQUEST_ID, response.getHeaders().get("x-amzn-RequestId"));
            awsResponse.setResponseMetadata(new ResponseMetadata(metadata));

            log.trace("Done parsing service response");
            return awsResponse;
          }
          catch (java.io.IOException e) {
            throw new RuntimeException(e);
          }
          catch (java.lang.Exception e) {
            throw new RuntimeException(e);
          }
          finally {
            if (!needsConnectionLeftOpen) {
              try {jsonParser.close();} catch (Exception e) {}
            }
          }
        });
      //});
    }

    protected void registerAdditionalMetadataExpressions(JsonUnmarshallerContext unmarshallerContext) {}

    public boolean needsConnectionLeftOpen() {
        return needsConnectionLeftOpen;
    }

}
