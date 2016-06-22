package org.grails.datastore.rx.rest

import com.damnhandy.uri.template.UriTemplate
import grails.gorm.rx.RxEntity
import grails.gorm.rx.rest.interceptor.RequestInterceptor
import grails.http.MediaType
import grails.http.client.RxHttpClientBuilder
import grails.http.client.cfg.DefaultConfiguration
import grails.http.client.exceptions.HttpClientException
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufHolder
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.Unpooled
import io.netty.handler.codec.base64.Base64
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.reactivex.netty.client.ConnectionProviderFactory
import io.reactivex.netty.client.Host
import io.reactivex.netty.client.pool.PoolConfig
import io.reactivex.netty.client.pool.SingleHostPoolingProviderFactory
import io.reactivex.netty.protocol.http.client.HttpClient
import io.reactivex.netty.protocol.http.client.HttpClientRequest
import io.reactivex.netty.protocol.http.client.HttpClientResponse
import org.bson.codecs.Codec
import org.bson.codecs.configuration.CodecRegistry
import org.grails.datastore.bson.codecs.BsonPersistentEntityCodec
import org.grails.datastore.bson.json.JsonReader
import org.grails.datastore.bson.json.JsonWriter
import org.grails.datastore.gorm.GormValidateable
import org.grails.datastore.mapping.config.ConfigurationUtils
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.query.Query
import org.grails.datastore.mapping.reflect.EntityReflector
import org.grails.datastore.mapping.validation.ValidationErrors
import org.grails.datastore.mapping.validation.ValidationException
import org.grails.datastore.rx.AbstractRxDatastoreClient
import org.grails.datastore.rx.batch.BatchOperation
import org.grails.datastore.rx.bson.CodecsRxDatastoreClient
import org.grails.datastore.rx.query.QueryState
import org.grails.datastore.rx.rest.api.RxRestGormStaticApi
import org.grails.datastore.rx.rest.codecs.ContextAwareCodec
import org.grails.datastore.rx.rest.config.RestClientMappingContext
import org.grails.datastore.rx.rest.query.BsonRxRestQuery
import org.grails.datastore.rx.rest.query.SimpleRxRestQuery
import org.grails.gorm.rx.api.RxGormEnhancer
import org.grails.gorm.rx.api.RxGormStaticApi
import org.springframework.core.convert.converter.Converter
import org.springframework.core.env.PropertyResolver
import org.springframework.validation.Errors
import rx.Observable
import rx.Subscriber
import rx.functions.Func2

import java.nio.charset.Charset
import java.text.SimpleDateFormat
/**
 * An RxGORM implementation that backs onto a backend REST server
 *
 * @author Graeme Rocher
 * @since 6.0
 */
@CompileStatic
@Slf4j
class RxRestDatastoreClient extends AbstractRxDatastoreClient<RxHttpClientBuilder> implements CodecsRxDatastoreClient<RxHttpClientBuilder> {

    public static final String SETTING_HOST = "grails.gorm.rest.host"
    public static final String SETTING_PORT = "grails.gorm.rest.port"
    public static final String SETTING_CHARSET = "grails.gorm.rest.charset"
    public static final String SETTING_POOL_OPTIONS = "grails.gorm.rest.pool.options"
    public static final String SETTING_USERNAME = "grails.gorm.rest.username"
    public static final String SETTING_PASSWORD = "grails.gorm.rest.password"
    public static final String SETTING_INTERCEPTORS = "grails.gorm.rest.interceptors"
    public static final String SETTING_QUERY_TYPE = "grails.gorm.rest.query.type"
    public static final String SETTING_ORDER_PARAMETER = "grails.gorm.rest.parameters.order"
    public static final String SETTING_EXPAND_PARAMETER = "grails.gorm.rest.parameters.expand"
    public static final String SETTING_SORT_PARAMETER = "grails.gorm.rest.parameters.sort"
    public static final String SETTING_MAX_PARAMETER = "grails.gorm.rest.parameters.max"
    public static final String SETTING_QUERY_PARAMETER = "grails.gorm.rest.parameters.query"
    public static final String SETTING_OFFSET_PARAMETER = "grails.gorm.rest.parameters.offset"


    public static final String DEFAULT_ORDER_PARAMETER = "order"
    public static final String DEFAULT_OFFSET_PARAMETER = "offset"
    public static final String DEFAULT_SORT_PARAMETER = "sort"
    public static final String DEFAULT_MAX_PARAMETER = "max"
    public static final String DEFAULT_EXPAND_PARAMETER = "expand"
    public static final String DEFAULT_QUERY_PARAMETER = "q"


    final ConnectionProviderFactory connectionProviderFactory
    final Observable<Host> defaultClientHost
    final CodecRegistry codecRegistry
    final String username
    final String password
    final Charset charset
    final String orderParameter
    final String queryParameter
    final String offsetParameter
    final String maxParameter
    final String sortParameter
    final String expandParameter
    final Set<String> defaultParameterNames
    final RxHttpClientBuilder rxHttpClientBuilder
    final List<RequestInterceptor> interceptors = []
    final Class<? extends SimpleRxRestQuery> queryType
    protected final boolean allowBlockingOperations


    RxRestDatastoreClient(SocketAddress serverAddress, PropertyResolver configuration, RestClientMappingContext mappingContext) {
        super(mappingContext)

        this.allowBlockingOperations = configuration.getProperty(SETTING_ALLOW_BLOCKING, Boolean, true)
        this.defaultClientHost = Observable.just(new Host(serverAddress))
        this.username = configuration.getProperty(SETTING_USERNAME, String, null)
        this.password = configuration.getProperty(SETTING_PASSWORD, String, null)
        this.charset = Charset.forName( configuration.getProperty(SETTING_CHARSET, "UTF-8"))
        this.queryType = (configuration.getProperty(SETTING_QUERY_TYPE, String, "simple") == "bson") ? BsonRxRestQuery : SimpleRxRestQuery
        def pool = new PoolConfig()
        // TODO: populate pool configuration
        connectionProviderFactory = SingleHostPoolingProviderFactory.create(pool)
        this.codecRegistry = mappingContext.codecRegistry
        def clientConfiguration = new DefaultConfiguration()
        clientConfiguration.setEncoding(charset.toString())
        this.rxHttpClientBuilder = new RxHttpClientBuilder(connectionProviderFactory, clientConfiguration)
        interceptors.addAll(
                ConfigurationUtils.findServices(configuration, SETTING_INTERCEPTORS, RequestInterceptor.class)
        )

        this.orderParameter = configuration.getProperty(SETTING_ORDER_PARAMETER, String, DEFAULT_ORDER_PARAMETER)
        this.offsetParameter = configuration.getProperty(SETTING_OFFSET_PARAMETER, String, DEFAULT_OFFSET_PARAMETER)
        this.sortParameter = configuration.getProperty(SETTING_SORT_PARAMETER, String, DEFAULT_SORT_PARAMETER)
        this.maxParameter = configuration.getProperty(SETTING_MAX_PARAMETER, String, DEFAULT_MAX_PARAMETER)
        this.queryParameter = configuration.getProperty(SETTING_QUERY_PARAMETER, String, DEFAULT_QUERY_PARAMETER)
        this.expandParameter = configuration.getProperty(SETTING_EXPAND_PARAMETER, String, DEFAULT_EXPAND_PARAMETER)
        this.defaultParameterNames = new HashSet<>()
        defaultParameterNames.add(sortParameter)
        defaultParameterNames.add(maxParameter)
        defaultParameterNames.add(offsetParameter)
        defaultParameterNames.add(orderParameter)
        defaultParameterNames.add(queryParameter)
        defaultParameterNames.add(expandParameter)

        initialize(mappingContext)
    }

    RxRestDatastoreClient(SocketAddress serverAddress, RestClientMappingContext mappingContext) {
        this(serverAddress, DatastoreUtils.createPropertyResolver(null), mappingContext)
    }

    RxRestDatastoreClient(SocketAddress serverAddress, PropertyResolver configuration, Class... classes) {
        this(serverAddress, configuration, createMappingContext(configuration, classes))
    }

    RxRestDatastoreClient(SocketAddress serverAddress, Class... classes) {
        this(serverAddress, createMappingContext(DatastoreUtils.createPropertyResolver(null), classes))
    }

    RxRestDatastoreClient(PropertyResolver configuration, RestClientMappingContext mappingContext) {
        this(createServerSocketAddress(configuration), configuration, mappingContext)
    }

    RxRestDatastoreClient(RestClientMappingContext mappingContext) {
        this(DatastoreUtils.createPropertyResolver(null), mappingContext)
    }

    RxRestDatastoreClient(PropertyResolver configuration, Class... classes) {
        this(configuration, createMappingContext(configuration, classes))
    }

    RxRestDatastoreClient(Class... classes) {
        this(createMappingContext(DatastoreUtils.createPropertyResolver(null), classes))
    }

    @Override
    RestClientMappingContext getMappingContext() {
        return (RestClientMappingContext)super.getMappingContext()
    }

    @Override
    Observable<Number> batchDelete(BatchOperation operation) {
        HttpClient httpClient = createHttpClient()
        List<HttpClientRequest> observables = []

        for (PersistentEntity entity in operation.deletes.keySet()) {
            RestEndpointPersistentEntity restEndpointPersistentEntity = (RestEndpointPersistentEntity)entity
            UriTemplate uriTemplate = restEndpointPersistentEntity.uriTemplate
            EntityReflector entityReflector = entity.getReflector()

            Map<Serializable, BatchOperation.EntityOperation> entityOperationMap = operation.deletes.get(entity)
            for (Serializable id in entityOperationMap.keySet()) {
                def object = entityOperationMap.get(id).object
                String uri = expandUri(uriTemplate, entityReflector, object)

                HttpClientRequest requestObservable = httpClient
                        .createDelete(uri)

                observables.add requestObservable
            }
        }
        if (observables.isEmpty()) {
            return Observable.just((Number) 0L)
        } else {
            return (Observable<Number>) Observable.concatEager(observables)
                    .reduce(0, { Integer count, HttpClientResponse response ->

                def status = response.getStatus()
                if (status == HttpResponseStatus.NO_CONTENT || status == HttpResponseStatus.OK) {
                    count++
                }
                else {
                    throw new HttpClientException("Server returned error response: $status, reason: ${status.reasonPhrase()} for host ${response.hostHeader}")
                }
                return (Number) count
            })
        }
    }

    HttpClient<ByteBuf, ByteBuf> createHttpClient() {
        return HttpClient.newClient(connectionProviderFactory, defaultClientHost)

    }


    @Override
    Observable<Number> batchWrite(BatchOperation operation) {
        HttpClient httpClient = createHttpClient()
        List<Observable> observables = []

        for(PersistentEntity entity in operation.inserts.keySet()) {
            RestEndpointPersistentEntity restEndpointPersistentEntity = (RestEndpointPersistentEntity)entity
            UriTemplate uriTemplate = restEndpointPersistentEntity.uriTemplate
            EntityReflector entityReflector = entity.getReflector()
            Map<Serializable, BatchOperation.EntityOperation> entityOperationMap = operation.inserts.get(entity)
            Class type = entity.getJavaClass()
            Codec codec = getCodecRegistry().get(type)
            String contentType = ((RestEndpointPersistentEntity) entity).getMapping().getMappedForm().contentType

            for(BatchOperation.EntityOperation entityOp in entityOperationMap.values()) {
                def object = entityOp.object
                String uri = expandUri(uriTemplate, entityReflector, object)
                Observable postObservable = httpClient.createPost(uri)
                postObservable = postObservable.setHeader( HttpHeaderNames.CONTENT_TYPE, contentType )
                                               .setHeader( HttpHeaderNames.ACCEPT, contentType)
                postObservable = prepareRequest(restEndpointPersistentEntity, postObservable, object)

                def interceptorArgument = operation.arguments.interceptor
                if(interceptorArgument instanceof RequestInterceptor) {
                    postObservable = ((RequestInterceptor)interceptorArgument).intercept(restEndpointPersistentEntity, (RxEntity)object, postObservable)
                }
                postObservable.writeContent(
                    createContentWriteObservable(restEndpointPersistentEntity, codec, entityOp)
                )
                postObservable = postObservable.map { HttpClientResponse response ->
                    return new ResponseAndEntity(uri, response, entity, object, codec)
                }
                observables.add(postObservable)
            }
        }

        for(PersistentEntity entity in operation.updates.keySet()) {
            RestEndpointPersistentEntity restEndpointPersistentEntity = (RestEndpointPersistentEntity)entity
            Map<Serializable, BatchOperation.EntityOperation> entityOperationMap = operation.updates.get(entity)
            Class type = entity.getJavaClass()
            Codec codec = getCodecRegistry().get(type)
            UriTemplate uriTemplate = restEndpointPersistentEntity.uriTemplate
            String contentType = ((RestEndpointPersistentEntity) entity).getMapping().getMappedForm().contentType
            EntityReflector entityReflector = entity.getReflector()

            for(Serializable id in entityOperationMap.keySet()) {
                BatchOperation.EntityOperation entityOp = entityOperationMap.get(id)
                def object = entityOp.object
                String uri = expandUri(uriTemplate, entityReflector, object)

                Observable putObservable = httpClient.createPut(uri)
                putObservable = putObservable.setHeader( HttpHeaderNames.CONTENT_TYPE, contentType )
                                             .setHeader( HttpHeaderNames.ACCEPT, contentType )
                putObservable = prepareRequest(restEndpointPersistentEntity, putObservable, object)
                def interceptorArgument = operation.arguments.interceptor
                if(interceptorArgument instanceof RequestInterceptor) {
                    putObservable = ((RequestInterceptor)interceptorArgument).intercept(restEndpointPersistentEntity, (RxEntity)object, putObservable)
                }

                putObservable.writeContent(
                    createContentWriteObservable(restEndpointPersistentEntity, codec, entityOp)
                )
                putObservable = putObservable.map { HttpClientResponse response ->
                    return new ResponseAndEntity(uri, response, entity, object, codec)
                }
                observables.add(putObservable)
            }
        }

        if(observables.isEmpty()) {
            return Observable.just((Number)0L)
        }
        else {
            return (Observable<Number>)Observable.concatEager(observables)
                .switchMap { ResponseAndEntity responseAndEntity ->
                    HttpClientResponse response = responseAndEntity.response
                    HttpResponseStatus status = response.status
                    String responseContentType = response.getHeader(HttpHeaderNames.CONTENT_TYPE)
                    MediaType mediaType = responseContentType != null ? new MediaType(responseContentType) : null
                    responseAndEntity.mediaType = mediaType

                    if(status == HttpResponseStatus.CREATED ) {
                        if(MediaType.JSON == mediaType) {
                            return Observable.combineLatest( Observable.just(responseAndEntity), response.content, { ResponseAndEntity res, Object content ->
                                res.content = content
                                return res
                            } as Func2<ResponseAndEntity, Object, ResponseAndEntity>)
                        }
                        else {
                            return Observable.just(responseAndEntity)
                        }
                    }
                    else if(status == HttpResponseStatus.OK) {
                        return Observable.just(responseAndEntity)
                    }
                    else if(status == HttpResponseStatus.UNPROCESSABLE_ENTITY ) {
                        if(MediaType.VND_ERROR == mediaType) {
                            return Observable.combineLatest( Observable.just(responseAndEntity), response.content, { ResponseAndEntity res, Object content ->
                                res.content = content
                                return res
                            } as Func2<ResponseAndEntity, Object, ResponseAndEntity>)
                        }
                        else {
                            return Observable.just(responseAndEntity)
                        }
                    }
                    else {
                        throw new HttpClientException("Server returned error response: $status, reason: ${status.reasonPhrase()}")
                    }
                }
                .reduce(0L, { Long count, ResponseAndEntity responseAndContent ->
                    HttpClientResponse response = responseAndContent.response
                    Object content = responseAndContent.content
                    HttpResponseStatus status = response.status
                    if(status == HttpResponseStatus.CREATED || status == HttpResponseStatus.OK) {
                        count++
                        if(content != null) {
                            ByteBuf byteBuf
                            if (content instanceof ByteBuf) {
                                byteBuf = (ByteBuf) content
                            } else if (content instanceof ByteBufHolder) {
                                byteBuf = ((ByteBufHolder) content).content()
                            } else {
                                throw new IllegalStateException("Received invalid response object: $content")
                            }

                            def reader = new InputStreamReader(new ByteBufInputStream(byteBuf))
                            Codec codec = responseAndContent.codec
                            try {
                                Object decoded = codec.decode(new JsonReader(reader), BsonPersistentEntityCodec.DEFAULT_DECODER_CONTEXT)

                                EntityReflector reflector = responseAndContent.entity.reflector
                                def identifier = reflector.getIdentifier(decoded)
                                reflector.setIdentifier(responseAndContent.object, identifier)
                            }
                            catch(Throwable e) {
                                log.error "Error querying [$responseAndContent.entity.name] object for URI [$responseAndContent.uri]", e
                                throw new HttpClientException("Error decoding entity $responseAndContent.entity.name from response: $e.message", e)
                            }
                            finally {
                                byteBuf.release()
                                reader.close()
                            }

                        }
                    }
                else if(status == HttpResponseStatus.UNPROCESSABLE_ENTITY) {
                    Errors errors

                    if(MediaType.VND_ERROR == responseAndContent.mediaType && content != null) {
                        try {
                            ByteBuf byteBuf
                            if (content instanceof ByteBuf) {
                                byteBuf = (ByteBuf) content
                            } else if (content instanceof ByteBufHolder) {
                                byteBuf = ((ByteBufHolder) content).content()
                            } else {
                                throw new IllegalStateException("Received invalid response object: $content")
                            }

                            def reader = new InputStreamReader(new ByteBufInputStream(byteBuf))
                            JsonReader jsonReader = new JsonReader(reader)
                            Codec<Errors> codec = mappingContext.get(Errors, codecRegistry)
                            def object = responseAndContent.object
                            if(codec instanceof ContextAwareCodec) {
                                ((ContextAwareCodec)codec).setContext(object)
                            }
                            errors = codec.decode(jsonReader, BsonPersistentEntityCodec.DEFAULT_DECODER_CONTEXT)
                            ((GormValidateable)object).setErrors(errors)
                        } catch (Throwable e) {
                            throw new HttpClientException("Error decoding validation errors from response: $e.message", e )
                        }

                    }
                    else {
                        errors = new ValidationErrors(responseAndContent.object, responseAndContent.entity.name)
                    }
                    throw new ValidationException("Validation error occured saving entity", errors)
                }
                return (Number)count
            })
        }
    }

    @Override
    void doClose() {
        // no-op
    }

    @Override
    Serializable generateIdentifier(PersistentEntity entity, Object instance, EntityReflector reflector) {
        // the identifier cannot be known since it will be assigned by the backend REST server, so use the hash code
        // for internal processing purposes
        return Integer.valueOf(instance.hashCode())
    }

    @Override
    Query createEntityQuery(PersistentEntity entity, QueryState queryState) {
        return queryType.newInstance(this, entity, queryState)
    }


    final Query createQuery(Class type, UriTemplate uriTemplate, QueryState queryState) {
        def entity = mappingContext.getPersistentEntity(type.name)
        if(entity == null) {
            throw new IllegalArgumentException("Type [$type.name] is not a persistent type")
        }

        return queryType.newInstance(this, entity, uriTemplate, queryState)
    }

    @Override
    RxHttpClientBuilder getNativeInterface() {
        return rxHttpClientBuilder
    }

    protected Observable<ByteBuf> createContentWriteObservable(RestEndpointPersistentEntity restEndpointPersistentEntity, Codec codec, BatchOperation.EntityOperation entityOp) {
        Observable.create({ Subscriber<ByteBuf> subscriber ->
            ByteBuf byteBuf = Unpooled.buffer()
            try {
                def writer = new OutputStreamWriter(new ByteBufOutputStream(byteBuf), restEndpointPersistentEntity.charset)
                def jsonWriter = new JsonWriter(writer)
                codec.encode(jsonWriter, entityOp.object, BsonPersistentEntityCodec.DEFAULT_ENCODER_CONTEXT)

                subscriber.onNext(byteBuf)
            } catch (Throwable e) {
                log.error "Error encoding object [$entityOp.object] to JSON: $e.message", e
                subscriber.onError(e)
            }
            finally {
                byteBuf.release()
                subscriber.onCompleted()
            }

        } as Observable.OnSubscribe<ByteBuf>)
    }

    protected String expandUri(UriTemplate uriTemplate, EntityReflector entityReflector, object) {
        Map<String, Object> vars = [:]
        for (var in uriTemplate.variables) {
            def value = entityReflector.getProperty(object, var)
            if(value instanceof RxEntity) {
                def id = getMappingContext().getProxyHandler().getIdentifier(value)
                if(id != null) {
                    vars.put(var, value)
                }
            }
            else if (value != null) {
                vars.put(var, value)
            }
        }

        String uri = uriTemplate.expand(vars)
        return uri
    }

    protected void initialize(RestClientMappingContext mappingContext) {
        for (entity in mappingContext.persistentEntities) {
            RxGormEnhancer.registerEntity(entity, this)
        }

        initDefaultConverters(mappingContext)
        initDefaultEventListeners(eventPublisher)
    }


    protected void initDefaultConverters(RestClientMappingContext mappingContext) {
        TimeZone UTC = TimeZone.getTimeZone("UTC");
        mappingContext.converterRegistry.addConverter(new Converter<String, Date>() {
            @Override
            Date convert(String source) {
                def format = new SimpleDateFormat(JsonWriter.ISO_8601)
                format.setTimeZone(UTC)
                return format.parse(source)
            }
        })
    }

    protected static InetSocketAddress createServerSocketAddress(PropertyResolver configuration) {
        new InetSocketAddress(configuration.getProperty(SETTING_HOST, String.class, "localhost"), configuration.getProperty(SETTING_PORT, Integer.class, 8080))
    }

    protected static RestClientMappingContext createMappingContext(PropertyResolver configuration, Class... classes) {
        return new RestClientMappingContext(configuration, classes)
    }

    HttpClientRequest<ByteBuf, ByteBuf> prepareRequest(RestEndpointPersistentEntity restEndpointPersistentEntity, HttpClientRequest<ByteBuf, ByteBuf> httpClientRequest, Object instance = null) {
        if (username != null && password != null) {
            String usernameAndPassword = "$username:$password"
            def encoded = Base64.encode(Unpooled.wrappedBuffer(usernameAndPassword.bytes)).toString(charset)
            httpClientRequest = httpClientRequest.addHeader HttpHeaderNames.AUTHORIZATION, "Basic $encoded".toString()
        }

        for(RequestInterceptor i in interceptors) {
            httpClientRequest = i.intercept(restEndpointPersistentEntity, (RxEntity)instance, httpClientRequest)
        }
        for(RequestInterceptor i in restEndpointPersistentEntity.interceptors) {
            httpClientRequest = i.intercept(restEndpointPersistentEntity, (RxEntity)instance, httpClientRequest)
        }
        return httpClientRequest
    }

    @Override
    boolean isAllowBlockingOperations() {
        return this.allowBlockingOperations
    }

    @Override
    RxGormStaticApi createStaticApi(PersistentEntity entity) {
        return new RxRestGormStaticApi(entity, this)
    }

    @Override
    def <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
        getMappingContext().get(clazz, codecRegistry)
    }

    private static class ResponseAndEntity {
        final String uri
        final HttpClientResponse response
        final PersistentEntity entity
        final Object object
        final Codec codec

        Object content
        MediaType mediaType

        ResponseAndEntity(String uri, HttpClientResponse response, PersistentEntity entity, Object object, Codec codec) {
            this.uri = uri
            this.response = response
            this.entity = entity
            this.object = object
            this.codec = codec
        }
    }

}
