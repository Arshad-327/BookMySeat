/**
 * Where the API is. The api-gateway, and nothing else.
 *
 * The frontend never calls auth-service (8081), event-service (8082) or booking-service
 * (8083) directly. Those ports take X-User-Id on trust; the gateway is what strips a
 * client-supplied identity and replaces it with the one the access token proves. A
 * frontend that reached past it would be relying on a trust model that only holds because
 * nothing does.
 *
 * The default is here rather than in a committed .env file because .env.* is git-ignored
 * (it is where secrets go), so a fresh clone has none.
 */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
