import { test } from "node:test";
import assert from "node:assert/strict";

import { apiBaseUrlFrom, problemWithHostedApiUrl, DEFAULT_API_BASE_URL } from "./apiBaseUrl.js";

test("unset or blank falls back to the local backend, as before", () => {
  assert.equal(apiBaseUrlFrom(undefined), DEFAULT_API_BASE_URL);
  assert.equal(apiBaseUrlFrom(""), DEFAULT_API_BASE_URL);
  assert.equal(apiBaseUrlFrom("   "), DEFAULT_API_BASE_URL);
});

test("a configured address is used as given, minus trailing slashes", () => {
  assert.equal(apiBaseUrlFrom("http://localhost:8080"), "http://localhost:8080");
  assert.equal(apiBaseUrlFrom("https://bloodlink-api.onrender.com/"), "https://bloodlink-api.onrender.com");
  assert.equal(apiBaseUrlFrom(" https://bloodlink-api.onrender.com// "), "https://bloodlink-api.onrender.com");
});

test("a hosted build refuses a missing address", () => {
  assert.match(problemWithHostedApiUrl(undefined), /VITE_API_BASE_URL is not set/);
  assert.match(problemWithHostedApiUrl(" "), /not set/);
});

test("a hosted build refuses a plain-http or schemeless address", () => {
  assert.match(problemWithHostedApiUrl("http://bloodlink-api.onrender.com"), /must start with https:\/\//);
  assert.match(problemWithHostedApiUrl("bloodlink-api.onrender.com"), /must start with https:\/\//);
});

test("a hosted build accepts an https address", () => {
  assert.equal(problemWithHostedApiUrl("https://bloodlink-api.onrender.com"), null);
});
