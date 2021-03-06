/*
 * Copyright (c) 1998-2016 Caucho Technology -- all rights reserved
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
 * @author Nam Nguyen
 */

package com.caucho.v5.autoconf.view;

import javax.inject.Inject;

import com.caucho.v5.config.IncludeOnClass;
import com.caucho.v5.config.Priority;
import com.caucho.v5.view.thymeleaf.ViewThymeleaf;

import io.baratine.config.Config;
import io.baratine.config.Include;
import io.baratine.inject.Bean;
import io.baratine.web.View;
import io.baratine.web.ViewResolver;

/**
 * mustache view configuration.
 */
@Include
@IncludeOnClass(org.thymeleaf.TemplateEngine.class)
public class IncludeThymeleaf
{
  private @Inject Config _config;

  public IncludeThymeleaf()
  {
  }

  @Bean
  @Priority(-10)
  public ViewResolver<View> thymeleaf()
  {
    return new ViewThymeleaf(_config);
  }
}
