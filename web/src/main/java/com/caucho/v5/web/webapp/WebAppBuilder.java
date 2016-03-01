/*
 * Copyright (c) 1998-2015 Caucho Technology -- all rights reserved
 *
 * This file is part of Baratine(TM)
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Baratine is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Baratine is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baratine; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.v5.web.webapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Provider;

import com.caucho.v5.amp.Amp;
import com.caucho.v5.amp.ServiceManagerAmp;
import com.caucho.v5.amp.ServiceRefAmp;
import com.caucho.v5.amp.journal.JournalFactoryAmp;
import com.caucho.v5.amp.manager.AutoBindService;
import com.caucho.v5.amp.queue.OutboxContext;
import com.caucho.v5.amp.spi.InboxAmp;
import com.caucho.v5.amp.spi.MessageAmp;
import com.caucho.v5.amp.spi.OutboxAmp;
import com.caucho.v5.amp.spi.ServiceManagerBuilderAmp;
import com.caucho.v5.amp.vault.ActorGeneratorVault;
import com.caucho.v5.config.Configs;
import com.caucho.v5.config.inject.BaratineProducer;
import com.caucho.v5.http.dispatch.InvocationRouter;
import com.caucho.v5.http.websocket.WebSocketManager;
import com.caucho.v5.inject.InjectManagerAmp;
import com.caucho.v5.inject.InjectManagerAmp.InjectBuilderRootAmp;
import com.caucho.v5.inject.type.TypeRef;
import com.caucho.v5.loader.EnvironmentClassLoader;
import com.caucho.v5.util.L10N;

import io.baratine.config.Config;
import io.baratine.config.Config.ConfigBuilder;
import io.baratine.convert.Convert;
import io.baratine.inject.Binding;
import io.baratine.inject.InjectManager;
import io.baratine.inject.InjectManager.BindingBuilder;
import io.baratine.inject.InjectManager.InjectAutoBind;
import io.baratine.inject.InjectManager.InjectBuilder;
import io.baratine.inject.Key;
import io.baratine.service.Service;
import io.baratine.service.ServiceManager;
import io.baratine.service.ServiceRef;
import io.baratine.service.Vault;
import io.baratine.web.HttpMethod;
import io.baratine.web.IncludeWeb;
import io.baratine.web.InstanceBuilder;
import io.baratine.web.OutBuilder;
import io.baratine.web.RequestWeb;
import io.baratine.web.ServiceWeb;
import io.baratine.web.ServiceWebSocket;
import io.baratine.web.ViewWeb;
import io.baratine.web.WebBuilder;
import io.baratine.web.WebResourceBuilder;
import io.baratine.web.WebSocketBuilder;

/**
 * Baratine's web-app instance builder
 */
public class WebAppBuilder
  implements WebBuilder
{
  private static final L10N L = new L10N(WebAppBuilder.class);
  private static final Logger log
    = Logger.getLogger(WebAppBuilder.class.getName());
  
  private static final Predicate<RequestWeb> TRUE = x->true;
  
  private static final Map<HttpMethod,Predicate<RequestWeb>> _methodMap;
  
  private final HttpBaratine _http;
  private EnvironmentClassLoader _classLoader;

  private ArrayList<RouteWebApp> _routes = new ArrayList<>();
  private ArrayList<ViewRef<?>> _views = new ArrayList<>();

  private Throwable _configException;

  //private ServiceManagerBuilder _serviceBuilder;

  private InjectBuilderRootAmp _injectBuilder;

  private WebAppFactory _factory;
  
  private ServiceManagerBuilderAmp _serviceBuilder;
  private ConfigBuilder _configBuilder;
  private WebAppAutoBind _autoBind;
  private WebApp _webApp;
  private WebSocketManager _wsManager;
  

  /**
   * Creates the host with its environment loader.
   */
  public WebAppBuilder(WebAppFactory factory)
  {
    Objects.requireNonNull(factory);
    
    _factory = factory;
    
    _http = factory.http();
    
    _classLoader = EnvironmentClassLoader.create(_http.classLoader(),
                                                 factory.id());
    
    Thread thread = Thread.currentThread();
    ClassLoader loader = thread.getContextClassLoader();
    
    OutboxAmp outbox = OutboxAmp.current();
    OutboxContext<MessageAmp> oldContext = null;
    
    if (outbox != null) {
      oldContext = outbox.context();
    }
    
    try {
      thread.setContextClassLoader(classLoader());
      
      _configBuilder = Configs.config();
      _configBuilder.add(factory.config());
      
      _injectBuilder = InjectManagerAmp.manager(classLoader());

      _injectBuilder.include(BaratineProducer.class);
      
      _serviceBuilder = ServiceManagerAmp.newManager();
      _serviceBuilder.name("webapp");
      //_serviceBuilder.setJournalFactory(new JournalFactoryImpl());
      addJournalFactory(_serviceBuilder);
      addActorResources(_serviceBuilder);
      _serviceBuilder.contextManager(true);
      
      
      ServiceManagerAmp serviceManager = _serviceBuilder.managerBuild();
      Amp.setContextManager(serviceManager);
      
      _injectBuilder.autoBind(new AutoBindService(serviceManager));
      
      if (outbox != null) {
        InboxAmp inbox = serviceManager.inboxSystem();
        // XXX: should set the inbox
        outbox.getAndSetContext(inbox);
        //System.out.println("OUTBOX-a: " + inbox + " " + serviceManager);
      }
      /*
      System.out.println("OUTBOX: " + outbox);
      if (outbox != null) {
        System.out.println("  CONTEXT: " + outbox.context());
        System.out.println("  INBOX: " + outbox.inbox());
      }
      Thread.dumpStack();
      */
      
      _wsManager = webSocketManager();
      
      new WebApp(this);
    } catch (Throwable e) {
      e.printStackTrace();
      log.log(Level.WARNING, e.toString(), e);
      
      configException(e);
      
      _webApp = new WebAppBaratineError(this);
    } finally {
      thread.setContextClassLoader(loader);
      
      if (outbox != null) {
        outbox.getAndSetContext(oldContext);
      }
    }
  }
  
  private static void addJournalFactory(ServiceManagerBuilderAmp builder)
  {
    try {
      JournalFactoryAmp factory;
      
      Class<?> journal = Class.forName("com.caucho.v5.amp.journal.JournalFactoryImpl");
      
      factory = (JournalFactoryAmp) journal.newInstance();

      builder.setJournalFactory(factory);
    } catch (Exception e) {
      log.finer(e.toString());
    }
  }
  
  protected void addActorResources(ServiceManagerBuilderAmp builder)
  {
    try {
      ActorGeneratorVault gen = new ActorGeneratorVault();
      
      builder.actorGenerator(gen);
    } catch (Exception e) {
      log.finer(e.toString());
    }
  }

  public WebSocketManager webSocketManager()
  {
    return _wsManager;
  }

  // @Override
  private void build()
  {
    Thread thread = Thread.currentThread();
    ClassLoader loader = thread.getContextClassLoader();
    OutboxAmp outbox = OutboxAmp.current();
    OutboxContext<MessageAmp> context = outbox.getAndSetContext(null);
    
    try {
      thread.setContextClassLoader(classLoader());
      
      /*
      if (configException() == null) {
        return new WebAppBaratine(this).start();
      }
      else {
        return new WebAppBaratineError(this).start();
      }
      */
    } catch (Throwable e) {
      log.log(Level.WARNING, e.toString(), e);
      
      configException(e);
      
      _webApp = new WebAppBaratineError(this);
    } finally {
      thread.setContextClassLoader(loader);
      outbox.getAndSetContext(context);
    }
  }
  
  void build(WebApp webApp)
  {
    Objects.requireNonNull(webApp);
    
    _webApp = webApp;
    
    _autoBind = new WebAppAutoBind(webApp);
    _injectBuilder.autoBind(_autoBind);
    _injectBuilder.bind(Config.class).toProvider(()->webApp.config());
    _injectBuilder.bind(InjectManager.class).toProvider(()->webApp.inject());
    _injectBuilder.bind(ServiceManager.class).toProvider(()->webApp.serviceManager());
    
    generateFromFactory();
    
    // defaults
    get("/**").to(WebStaticFile.class);
  }
  
  //@Override
  public String id()
  {
    return _factory.id();
  }

  public String path()
  {
    return _factory.path();
  }

  public EnvironmentClassLoader classLoader()
  {
    return _classLoader;
  }
  
  /*
  public WebAppBaratineHttp getWebAppHttp()
  {
    return _webAppHttp;
  }
  */
  
  //
  // deployment
  //

  Config config()
  {
    return _configBuilder.get();
  }

  InjectBuilderRootAmp injectBuilder()
  {
    return _injectBuilder;
  }
  
  ServiceManagerBuilderAmp serviceBuilder()
  {
    ServiceManagerBuilderAmp builder = _serviceBuilder;
    
    return builder; 
  }

  private void configException(Throwable e)
  {
    if (_configException == null) {
      log.log(Level.FINER, e.toString(), e);
      
      _configException = e;
    }
    else {
      log.log(Level.FINER, e.toString(), e);
    }
  }

  //@Override
  public Throwable configException()
  {
    return _configException;
  }
  
  private void generateFromFactory()
  {
    for (IncludeWeb include : _factory.includes()) {
      include.build(this);
    }
  }
  
  /**
   * Builds the web-app's router
   */
  public InvocationRouter<InvocationBaratine> buildRouter(WebApp webApp)
  {
    // find views
    
    InjectManagerAmp inject = webApp.inject();
    
    for (Binding<ViewWeb> binding : inject.bindings(ViewWeb.class)) {
      try {
        ViewWeb<?> view = (ViewWeb<?>) binding.provider().get();
      
        Key<ViewWeb<?>> key = (Key) binding.key();

        view(view, key);
      } catch (Exception e) {
        log.log(Level.FINE, e.toString(), e);
      }
    }
    
    ArrayList<RouteMap> mapList = new ArrayList<>();
    
    ServiceManagerAmp manager = webApp.serviceManager();

    ServiceRefAmp serviceRef = manager.newService(new RouteService()).ref();
    
    for (RouteWebApp route : _routes) {
      mapList.add(route.toMap(inject, serviceRef));
    }

    /*
    for (RouteConfig config : _routeList) {
      RouteBaratine route = config.buildRoute(); 
      
      mapList.add(new RouteMap("", route));
    }
    */
    
    RouteMap []routeArray = new RouteMap[mapList.size()];
    
    mapList.toArray(routeArray);
    
    return new InvocationRouterWebApp(webApp, routeArray);
  }
  
  /**
   * Dummy to own the service.
   */
  private class RouteService
  {
  }

  @Override
  public WebBuilder include(Class<?> type)
  {
    System.out.println("ROUTER: " + type);
    IncludeWeb gen = (IncludeWeb) inject().instance(type);
      
    System.out.println("GEN: " + gen);
      
    return this;
  }
  
  //
  // inject
  //

  @Override
  public <T> BindingBuilder<T> bind(Class<T> api)
  {
    return _injectBuilder.bind(api);
  }

  @Override
  public <T> BindingBuilder<T> bind(Key<T> key)
  {
    return _injectBuilder.bind(key);
  }

  @Override
  public <S,T> Convert<S,T> converter(Class<S> source, Class<T> target)
  {
    return _injectBuilder.get().converter(source, target);
  }

  @Override
  public <T> ServiceRef.ServiceBuilder service(Class<T> api)
  {
    if (Vault.class.isAssignableFrom(api)) {
      addResourceConverter(api);
    }
    
    if (_webApp != null && _webApp.serviceManager() != null) {
      return _webApp.serviceManager().newService(api);
    }
    
    ServiceRef.ServiceBuilder builder = _serviceBuilder.service(api);

    return builder;
  }

  @Override
  public ServiceRef.ServiceBuilder service(Key<?> key, Class<?> api)
  {
    ServiceRef.ServiceBuilder builder = _serviceBuilder.service(key, api);
    
    if (Vault.class.isAssignableFrom(api)) {
      addResourceConverter(api);
    }

    return builder;
  }
  
  private void addResourceConverter(Class<?> api)
  {
    TypeRef resourceRef =  TypeRef.of(api).to(Vault.class);
    Class<?> idType = resourceRef.param(0).rawClass();
    Class<?> itemType = resourceRef.param(1).rawClass();
    
    Service service = api.getAnnotation(Service.class);
    
    String address = "";
    
    if (service != null) {
      address = service.value();
    }
    
    if (address.isEmpty()) {
      address = "/" + itemType.getSimpleName();
    }
    
    TypeRef convertRef = TypeRef.of(Convert.class, String.class, itemType);
    
    Convert<String,?> convert
       = new ConvertResource(address, itemType);
    
    bind(Key.of(convertRef.type())).to(convert);
  }

  @Override
  public <X> ServiceRef.ServiceBuilder service(Supplier<? extends X> supplier)
  {
    ServiceRef.ServiceBuilder builder = _serviceBuilder.service(supplier);

    return builder;
  }

  @Override
  public WebResourceBuilder route(HttpMethod method, String path)
  {
    RoutePath route = new RoutePath(method, path);

    _routes.add(route);
    
    return route;
  }

  @Override
  public WebSocketBuilder websocket(String path)
  {
    WebSocketPath route = new WebSocketPath(path);
    
    _routes.add(route);
    
    return route;
  }
  
  //
  // views
  //

  @Override
  public <T> WebBuilder view(ViewWeb<T> view)
  {
    _views.add(new ViewRef<>(view));
    
    return this;
  }

  @Override
  public <T> WebBuilder view(Class<? extends ViewWeb<T>> viewType)
  {
    ViewWeb<?> view = inject().instance(viewType);
    
    return view(view, Key.of(viewType));
  }

  private <T> WebBuilder view(ViewWeb<T> view, Key key)
  {
    _views.add(new ViewRef<>(view, key.type()));
    
    return this;
  }
  
  List<ViewRef<?>> views()
  {
    return _views;
  }

  BodyResolver bodyResolver()
  {
    return new BodyResolverBase();
  }
  
  @Override
  public InjectManagerAmp inject()
  {
    return _injectBuilder.get();
  }

  @Override
  public InjectBuilder autoBind(InjectAutoBind autoBind)
  {
    throw new UnsupportedOperationException();
  }

  // @Override
  public WebApp get()
  {
    Thread thread = Thread.currentThread();
    ClassLoader loader = thread.getContextClassLoader();
    OutboxAmp outbox = OutboxAmp.current();
    OutboxContext<MessageAmp> context = outbox.getAndSetContext(null);
    
    try {
      thread.setContextClassLoader(classLoader());
      
      if (configException() == null) {
        return _webApp.start();
      }
      else {
        return new WebAppBaratineError(this).start();
      }
      
    } catch (Throwable e) {
      log.log(Level.WARNING, e.toString(), e);
      
      configException(e);
      
      return new WebAppBaratineError(this);
    } finally {
      thread.setContextClassLoader(loader);
      outbox.getAndSetContext(context);
    }
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "[" + id() + "]";
  }
  
  private class WebAppAutoBind implements InjectAutoBind
  {
    private WebApp _webApp;
    
    WebAppAutoBind(WebApp webApp)
    {
      _webApp = webApp;
    }
    
    @Override
    public <T> Provider<T> provider(InjectManager manager, Key<T> key)
    {
      Class<?> rawClass = key.rawClass();
      
      return null;
    }
    
  }
  
  /**
   * RouteWebApp is a program for routes in the web-app.
   *
   */
  static interface RouteWebApp
  {
    RouteMap toMap(InjectManagerAmp inject, ServiceRefAmp serviceRef);
  }
  
  /**
   * RoutePath has a path pattern for a route.
   */
  class RoutePath implements WebResourceBuilder, RouteWebApp, OutBuilder
  {
    private HttpMethod _method;
    private String _path;
    private ServiceWeb _service;
    private Class<? extends ServiceWeb> _serviceClass;
    private ViewRef<?> _viewRef;
    
    RoutePath(HttpMethod method, String path)
    {
      _method = method;
      _path = path;
    }

    @Override
    public OutBuilder to(ServiceWeb service)
    {
      Objects.requireNonNull(service);
      
      _service = service;

      return this;
    }

    @Override
    public OutBuilder to(Class<? extends ServiceWeb> serviceClass)
    {
      Objects.requireNonNull(serviceClass);
      
      _serviceClass = serviceClass;

      return this;
    }
    
    @Override
    public <T> OutBuilder view(ViewWeb<T> view)
    {
      Objects.requireNonNull(view);
      
      _viewRef = new ViewRef(view);
      
      return this;
    }
    
    @Override
    public RouteMap toMap(InjectManagerAmp inject,
                          ServiceRefAmp serviceRef)
    {
      ArrayList<ViewRef<?>> views = new ArrayList<>();
      
      if (_viewRef != null) {
        views.add(_viewRef);
      }
      
      views.addAll(views());
      
      ServiceWeb service;
      
      if (_service != null) {
        service = _service;
      }
      else if (_serviceClass != null) {
        service = inject.instance(_serviceClass);
      }
      else {
        throw new IllegalStateException();
      }
      
      RouteApply routeApply;
      
      HttpMethod method = _method;
      
      if (method == null) {
        method = HttpMethod.UNKNOWN;
      }

      Predicate<RequestWeb> test = _methodMap.get(method);
        
      routeApply = new RouteApply(service, serviceRef, test, views);
      
      return new RouteMap(_path, routeApply);
    }
  }
  
  /**
   * WebSocketPath is a route to a websocket service.
   */
  static class WebSocketPath implements WebSocketBuilder, RouteWebApp
  {
    private String _path;
    
    private Supplier<? extends ServiceWebSocket<?,?>> _serviceFactory;
    private Class<? extends ServiceWebSocket<?,?>> _serviceType;
    
    WebSocketPath(String path)
    {
      _path = path;
    }

    @Override
    public <T,S> void to(ServiceWebSocket<T,S> service)
    {
      _serviceFactory = ()->service;
    }
    
    @Override
    public RouteMap toMap(InjectManagerAmp inject,
                          ServiceRefAmp serviceRef)
    {
      Supplier<? extends ServiceWebSocket<?,?>> factory = _serviceFactory;
      
      if (factory == null && _serviceType != null) {
        factory = ()->inject.instance(_serviceType);
      }
      
      WebSocketApply routeApply = new WebSocketApply(factory, serviceRef);
      
      return new RouteMap(_path, routeApply);
    }

    @Override
    public <T,S> InstanceBuilder<ServiceWebSocket<T,S>>
    to(Class<? extends ServiceWebSocket<T,S>> type)
    {
      _serviceType = type;
      
      return null;
    }

    @Override
    public <T,S> void to(Supplier<? extends ServiceWebSocket<T,S>> supplier)
    {
      _serviceFactory = supplier;
    }
  }
  
  private static class MethodPredicate implements Predicate<RequestWeb> {
    private HttpMethod _method;
    
    MethodPredicate(HttpMethod method)
    {
      Objects.requireNonNull(method);
      
      _method = method;
    }
    
    public boolean test(RequestWeb request)
    {
      return _method.name().equals(request.method());
    }
  }
  
  private static class MethodGet implements Predicate<RequestWeb> {
    @Override
    public boolean test(RequestWeb request)
    {
      return "GET".equals(request.method()) || "HEAD".equals(request.method());
    }
  }
  
  static class ConvertResource<T> implements Convert<String,T>
  {
    private ServiceManager _manager;
    private String _address;
    private Class<T> _itemType;
    
    ConvertResource(String address, Class<T> itemType)
    {
      if (! address.endsWith("/")) {
        address = address + "/";
      }
      
      _address = address;
      _itemType = itemType;
    }
    
    @Override
    public T convert(String key)
    {
      return manager().lookup(_address + key).as(_itemType);
    }
    
    private ServiceManager manager()
    {
      if (_manager == null) {
        _manager = ServiceManager.current();
      }
      
      return _manager;
    }
  }
  
  static {
    _methodMap = new HashMap<>();
    
    for (HttpMethod method : HttpMethod.values()) {
      _methodMap.put(method, new MethodPredicate(method));
    }
    
    _methodMap.put(HttpMethod.GET, new MethodGet());
    _methodMap.put(HttpMethod.UNKNOWN, TRUE);
  }
}