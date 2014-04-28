/**
 * Copyright (c) 2012-2013, JCabi.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the jcabi.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jcabi.dynamodb.maven.plugin;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.util.Tables;
import com.jcabi.log.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedList;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Creates DynamoDB tables.
 *
 * @author Carlos Miranda (miranda.cma@gmail.com)
 * @version $Id$
 * @checkstyle ClassDataAbstractionCoupling (300 lines)
 * @checkstyle MultipleStringLiterals (300 lines)
 */
@ToString
@EqualsAndHashCode(callSuper = false)
@Mojo(
    threadSafe = true, name = "create-table",
    defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST
)
public final class CreateTableMojo extends AbstractDynamoMojo {

    /**
     * The location of the tables to be created, in JSON format.
     */
    @Parameter(required = false)
    private transient Collection<String> tables = new LinkedList<String>();

    /**
     * AWS endpoint, use localhost if not specified.
     */
    @Parameter(required = false, defaultValue = "localhost")
    private transient String endpoint;

    /**
     * AWS key.
     */
    @Parameter(required = false, defaultValue = "")
    private transient String key;

    /**
     * AWS secret.
     */
    @Parameter(required = false, defaultValue = "")
    private transient String secret;

    @Override
    protected void run(final Instances instances) throws MojoFailureException {
        final AmazonDynamoDB aws = new AmazonDynamoDBClient(
            new BasicAWSCredentials(this.key, this.secret)
        );
        aws.setEndpoint(this.endpoint);
        for (final String table : this.tables) {
            final JsonObject json = CreateTableMojo.readJson(table);
            if (json.containsKey("TableName")) {
                final String name = json.getString("TableName");
                if (Tables.doesTableExist(aws, name)) {
                    Logger.info(
                        this, "Table '%s' already exists, skipping...", table
                    );
                } else {
                    this.createTable(aws, json);
                }
            } else {
                throw new MojoFailureException(
                    String.format(
                        "File '%s' does not specify TableName attribute", table
                    )
                );
            }
        }
    }

    /**
     * Create DynamoDB table.
     *
     * @param aws DynamoDB client
     * @param json JSON definition of table
     * @checkstyle ExecutableStatementCount (50 lines)
     */
    private void createTable(final AmazonDynamoDB aws,  final JsonObject json) {
        final String name = json.getString("TableName");
        final CreateTableRequest request =
            new CreateTableRequest().withTableName(name);
        if (json.containsKey("KeySchema")) {
            final Collection<KeySchemaElement> keys =
                new LinkedList<KeySchemaElement>();
            final JsonArray schema = json.getJsonArray("KeySchema");
            for (final JsonValue value : schema) {
                final JsonObject element = (JsonObject) value;
                keys.add(
                    new KeySchemaElement(
                        element.getString("AttributeName"),
                        element.getString("KeyType")
                    )
                );
            }
            request.setKeySchema(keys);
        }
        if (json.containsKey("AttributeDefinitions")) {
            final Collection<AttributeDefinition> attrs =
                new LinkedList<AttributeDefinition>();
            final JsonArray schema =
                json.getJsonArray("AttributeDefinitions");
            for (final JsonValue value : schema) {
                final JsonObject defn = (JsonObject) value;
                attrs.add(
                    new AttributeDefinition(
                        defn.getString("AttributeName"),
                        defn.getString("AttributeType")
                    )
                );
            }
            request.setAttributeDefinitions(attrs);
        }
        if (json.containsKey("ProvisionedThroughput")) {
            final JsonObject throughput =
                json.getJsonObject("ProvisionedThroughput");
            request.setProvisionedThroughput(
                new ProvisionedThroughput(
                    Long.parseLong(
                        throughput.getString("ReadCapacityUnits")
                    ),
                    Long.parseLong(
                        throughput.getString("WriteCapacityUnits")
                    )
                )
            );
        }
        aws.createTable(request);
        Logger.info(this, "Waiting for table %s to become active", name);
        Tables.waitForTableToBecomeActive(aws, name);
        Logger.info(this, "Table %s is now ready for use", name);
    }

    /**
     * Reads a file's contents into a JsonObject.
     * @param file The path of the file to read
     * @return The JSON object
     */
    private static JsonObject readJson(final String file) {
        InputStream stream = null;
        JsonObject json = null;
        try {
            stream = CreateTableMojo.class.getResourceAsStream(file);
            json = Json.createReader(stream).readObject();
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (final IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
        return json;
    }

}
