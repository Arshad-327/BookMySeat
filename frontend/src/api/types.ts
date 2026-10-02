/**
 * The API's response and request shapes, written by hand from the Java DTOs.
 *
 * HAND-WRITTEN, NOT GENERATED, and on purpose. The OpenAPI specs are served by three
 * services on 8081-8083 - ports this frontend must never call - and there is no aggregate
 * behind the gateway. More to the point, nullability here was decided field by field and
 * written into the DTOs' javadoc ("never null", "null on a booking held before V4"), which
 * is exactly what these types encode and what a generator would have to guess at.
 *
 * The cost is drift: nothing fails when a Java record changes. So each interface names the
 * file it mirrors. Change the record, change the interface in the same commit.
 *
 * Conventions: an Instant arrives as an ISO-8601 string in UTC with a trailing Z. Money
 * (BigDecimal) arrives as a JSON number.
 */

/**
 * The one error body every service and the gateway emit.
 * Mirrors: {auth,event,booking}-service .../dto/response/ErrorResponse.java and
 * api-gateway .../dto/response/ErrorResponse.java - the same five fields in all four.
 */
export interface ErrorResponse {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
}

/** Mirrors: auth-service .../dto/request/RegisterRequest.java */
export interface RegisterRequest {
  email: string
  /** 8 to 100 characters. */
  password: string
  /** Optional, at most 120 characters. */
  fullName?: string
}

/** Mirrors: auth-service .../dto/request/LoginRequest.java */
export interface LoginRequest {
  email: string
  password: string
}

/**
 * Tokens, and nothing about the user - GET /api/auth/me is how the app learns who that is.
 * Mirrors: auth-service .../dto/response/AuthResponse.java
 */
export interface AuthResponse {
  accessToken: string
  /** Opaque, and ROTATED on every use: the one presented to /refresh is revoked. */
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
}

/** Mirrors: auth-service .../dto/response/UserResponse.java */
export interface UserResponse {
  id: number
  email: string
  /** The column is nullable: registration does not require a name. */
  fullName: string | null
  role: string
  createdAt: string | null
}

/**
 * The page envelope, identical for GET /api/events and GET /api/bookings.
 * Mirrors: event-service and booking-service .../dto/response/PageResponse.java
 */
export interface PageResponse<T> {
  content: T[]
  /** Zero-based. */
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** Mirrors: event-service .../dto/response/VenueResponse.java */
export interface VenueResponse {
  id: number
  name: string
  city: string
  /** venues.address is a nullable column. */
  address: string | null
}

/**
 * One card on the browse page.
 * Mirrors: event-service .../dto/response/EventSummaryResponse.java
 */
export interface EventSummaryResponse {
  id: number
  title: string
  category: string
  /** Nullable column; and a URL that is present may still not resolve. Always have a fallback. */
  posterUrl: string | null
  venue: VenueResponse
  /**
   * NEVER NULL, by construction: the list contains only events with an upcoming show. So
   * there is no "no upcoming shows" card to render. That guarantee belongs to the EXISTS
   * filter in event-service's EventSpecifications - if it is ever loosened, this type is
   * the first thing that becomes a lie.
   */
  nextShowStartsAt: string
  /**
   * Never null. The lowest BASE price among upcoming shows - not the cheapest seat still
   * on sale. Label it "from", never "cheapest".
   */
  fromPrice: number
}
