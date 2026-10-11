/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.filters

import javax.inject.Inject
import play.api.http.DefaultHttpFilters
import play.filters.gzip.GzipFilter

/**
  * @author cuthbertm
  */
class Filters @Inject() (
    routeHeaderFilter: RouteHeaderFilter,
    // Play's CORSFilter, wrapped for the mobile admin origin (unchanged when mobile OAuth is off).
    corsFilter: org.maproulette.auth.mobile.MobileCorsFilter,
    gzipFilter: GzipFilter,
    httpLoggingFilter: HttpLoggingFilter,
    mobileBearerFilter: org.maproulette.auth.mobile.MobileBearerFilter
) extends DefaultHttpFilters(
      routeHeaderFilter,
      corsFilter,
      gzipFilter,
      httpLoggingFilter,
      mobileBearerFilter
    )
