#!/usr/bin/env python3
"""Generate an OpenAPI spec and optional credentials extract from a Tablo HTTP PCAP."""

import argparse
import json
import re
import subprocess
import sys
import textwrap
from collections import defaultdict
from pathlib import Path

STREAM_PATHS = {
  '/stream/pl.m3u8': 'application/vnd.apple.mpegurl',
  '/stream/pls.m3u8': 'application/vnd.apple.mpegurl',
  '/stream/segw.ts': 'video/mp2t',
}

PATH_DESCRIPTIONS = {
  '/server/info': 'Device identity, firmware version, and hardware model',
  '/server/location': 'Geographic location / lineup configuration',
  '/server/harddrives': 'Attached storage devices',
  '/server/guide/status': 'EPG download/seed status and coverage window',
  '/server/guide/refresh': 'Trigger guide data refresh',
  '/server/update/info': 'Available firmware update metadata',
  '/server/update/check': 'Check for firmware updates',
  '/settings/info': 'User preferences (LED, audio, auto-delete, etc.)',
  '/settings/recording_qualities/live': 'Available live recording quality presets',
  '/settings/recording_qualities/recordings': 'Available recording quality presets',
  '/guide/airings': 'Scheduled single-airing recordings',
  '/guide/shows': 'Series-level recording rules',
  '/guide/{airingId}': 'Detailed guide entry for a specific airing',
  '/guide/{showId}': 'Series/show recording rule details',
  '/guide/{showId}/airings': 'Airings associated with a series/show recording rule',
  '/guide/channels/{channelId}/watch': 'Start live TV playback; returns player session and HLS playlist URL',
  '/views/guide/channels/{channelId}/airings': 'EPG airings for a channel on a given date',
  '/views/guide/upcoming': 'Upcoming scheduled recordings across all channels',
  '/views/recordings/recent': 'Recently recorded content grouped by date',
  '/views/recordings/failed': 'Failed recordings',
  '/recordings/series': 'All recorded series summaries',
  '/recordings/series/{id}': 'Single series summary with episode list paths',
  '/recordings/series/{id}/episodes': 'Episode paths for a recorded series',
  '/recordings/series/{id}/seasons': 'Season paths for a recorded series',
  '/recordings/series/seasons/{id}': 'Single season metadata',
  '/recordings/series/seasons/{id}/episodes': 'Episode paths for a recorded season',
  '/recordings/series/episodes/{id}': 'Full episode metadata including video and channel details',
  '/recordings/series/episodes/{id}/watch': 'Start recorded episode playback; returns player session and HLS playlist URL',
  '/recordings/series/{id}/delete': 'Bulk-delete recordings for a series (e.g. watched episodes)',
  '/recordings/channels/{id}': 'Recorded channel metadata',
  '/recordings/movies': 'Recorded movies',
  '/recordings/shows': 'Recorded shows (non-series)',
  '/recordings/sports': 'Recorded sports events',
  '/player/sessions/{sessionId}': 'Active player session state and playlist URL',
  '/player/sessions/{sessionId}/keepalive': 'Extend player session TTL',
  '/notifications/stream': 'Long-lived notification stream (SSE or chunked HTTP)',
  '/images/{id}': 'Program or channel artwork (JPEG)',
  '/stream/pl.m3u8': 'HLS master playlist for active playback session',
  '/stream/pls.m3u8': 'HLS sub-playlist (variant stream)',
  '/stream/segw.ts': 'HLS MPEG-TS media segment',
}


def tshark(pcap, display_filter, *fields):
  cmd = ['tshark', '-r', str(pcap), '-Y', display_filter, '-T', 'fields',
         '-E', 'separator=\t']
  for field in fields:
    cmd.extend(['-e', field])
  result = subprocess.run(cmd, capture_output=True, text=True)
  if result.returncode != 0:
    raise RuntimeError(f'tshark failed: {result.stderr.strip()}')
  return [line for line in result.stdout.splitlines() if line.strip()]


def normalize_path(uri, port):
  path = uri.split('?')[0]
  if port == '80':
    return path if path in STREAM_PATHS else path
  path = re.sub(r'/C[0-9]+_SHOW_[A-Z0-9]+', '/{showId}', path)
  path = re.sub(r'/[0-9]+(?=/|$)', '/{id}', path)
  path = re.sub(r'/LH-[^/]+', '/{airingId}', path)
  path = re.sub(r'/S[0-9]+_[0-9]+_[0-9]+', '/{channelId}', path)
  path = re.sub(r'/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}', '/{sessionId}', path)
  return path if path.startswith('/') else f'/{path}'


def is_valid_path(path):
  return bool(path) and path not in ('200', '/200') and path.startswith('/')


def parse_query(uri):
  if '?' not in uri:
    return {}
  params = defaultdict(set)
  for part in uri.split('?', 1)[1].split('&'):
    if '=' in part:
      key, value = part.split('=', 1)
      params[key].add(value)
    else:
      params[part].add('')
  return params


def infer_schema(obj, depth=0):
  if depth > 12:
    return {'type': 'object'}
  if obj is None:
    return {'type': ['string', 'null']}
  if isinstance(obj, bool):
    return {'type': 'boolean'}
  if isinstance(obj, int):
    return {'type': 'integer'}
  if isinstance(obj, float):
    return {'type': 'number'}
  if isinstance(obj, str):
    schema = {'type': 'string'}
    if re.match(r'^\d{4}-\d{2}-\d{2}$', obj):
      schema['format'] = 'date'
    elif re.match(r'^\d{4}-\d{2}-\d{2}T', obj):
      schema['format'] = 'date-time'
    elif re.match(r'^[a-f0-9-]{36}$', obj, re.I):
      schema['format'] = 'uuid'
    return schema
  if isinstance(obj, list):
    if not obj:
      return {'type': 'array', 'items': {'type': 'string'}}
    return {'type': 'array', 'items': infer_schema(obj[0], depth + 1)}
  if isinstance(obj, dict):
    props = {key: infer_schema(value, depth + 1) for key, value in obj.items()}
    required = [key for key, value in obj.items() if value is not None]
    schema = {'type': 'object', 'properties': props}
    if required:
      schema['required'] = required
    return schema
  return {'type': 'string'}


def sanitize_schema(schema):
  if not isinstance(schema, dict):
    return {'type': 'string'}
  if '$ref' in schema:
    return {'$ref': schema['$ref']}
  if not schema:
    return {'type': 'string'}

  out = dict(schema)
  if out.get('nullable') is True:
    out.pop('nullable', None)
    base_type = out.get('type', 'string')
    if isinstance(base_type, list):
      out['type'] = base_type if 'null' in base_type else [*base_type, 'null']
    else:
      out['type'] = [base_type, 'null']

  if 'properties' in out:
    out['properties'] = {key: sanitize_schema(value) for key, value in out['properties'].items()}
  if 'items' in out:
    items = out['items']
    out['items'] = {'type': 'string'} if items == {} else sanitize_schema(items)
  if out.get('type') == 'array' and 'items' not in out:
    out['items'] = {'type': 'string'}
  if out.get('type') == 'object' and 'properties' not in out:
    out['properties'] = {}
  return out


def merge_schemas(left, right):
  if not left:
    return right or {}
  if not right:
    return left
  if left.get('type') == 'array' or right.get('type') == 'array':
    return {'type': 'array', 'items': merge_schemas(left.get('items', {}), right.get('items', {}))}
  left_props = left.get('properties', {})
  right_props = right.get('properties', {})
  if left_props or right_props:
    props = dict(left_props)
    for key, value in right_props.items():
      props[key] = merge_schemas(props.get(key, {}), value)
    both = set(left.get('required', [])) & set(right.get('required', []))
    schema = {'type': 'object', 'properties': props}
    if both:
      schema['required'] = sorted(both)
    return schema
  return left


def schema_name(method, path, suffix='Response'):
  clean = re.sub(r'[{}]', '', path).strip('/').replace('/', '_')
  parts = [part.capitalize() for part in re.split(r'[_/.-]', clean) if part]
  return ''.join(parts) + method.capitalize() + suffix


def query_param_spec(name, values):
  spec = {'name': name, 'in': 'query', 'required': False}
  if name == 'lh':
    spec['description'] = 'Bare flag parameter (no value). Present on most authenticated API calls.'
    spec['schema'] = {'type': 'boolean'}
  elif name == 'state':
    spec['schema'] = {'type': 'string', 'enum': sorted(values) or ['requested']}
  elif name == 'date':
    spec['schema'] = {'type': 'string', 'format': 'date'}
  elif name == 'sort':
    spec['schema'] = {'type': 'string', 'enum': sorted(values) or ['age']}
  elif name == 'order':
    spec['schema'] = {'type': 'string', 'enum': sorted(values) or ['asc', 'desc']}
  elif name in ('failed', 'allowAudioTranscode'):
    spec['schema'] = {'type': 'boolean'}
  elif name in ('client_type', 'client_version', 'client_build', 'device_type', 'fmt'):
    spec['schema'] = {'type': 'string'}
  elif name == 'device_id':
    spec['schema'] = {'type': 'string', 'format': 'uuid'}
  else:
    spec['schema'] = {'type': 'string'}
    spec['description'] = 'Opaque stream/session token from playlist_url or watch response'
  return spec


def parse_row(line, width):
  parts = line.split('\t')
  while len(parts) < width:
    parts.append('')
  return parts[:width]


def decode_http_body(hexdata):
  if not hexdata:
    return None
  cleaned = hexdata.strip().strip('"')
  if not cleaned:
    return None
  return bytes.fromhex(cleaned)


def extract_device_info(pcap):
  rows = tshark(pcap, 'http.request.uri == "/server/info" && http.response.code == 200', 'http.file_data')
  for row in rows:
    parts = parse_row(row, 1)
    raw = decode_http_body(parts[0])
    if raw:
      return json.loads(raw)
  return {}


def extract_credentials(pcap, output_path):
  lines = []
  lines.append('=' * 72)
  lines.append('Tablo TV 4th Gen — Extracted Credentials & Identifiers')
  lines.append(f'Source: {Path(pcap).name}')
  lines.append('WARNING: Sensitive data. Do not commit or share publicly.')
  lines.append('=' * 72)
  lines.append('')

  info = extract_device_info(pcap)
  if info:
    lines.append('DEVICE')
    lines.append('-' * 40)
    for key in ('server_id', 'name', 'version', 'build_number', 'local_address', 'cache_key'):
      lines.append(f'{key + ":":14}{info.get(key)}')
    model = info.get('model', {})
    lines.append(f'{"model":<14}{model.get("name")}')
    lines.append(f'{"model_type":<14}{model.get("type")}')
    lines.append(f'{"tuners":<14}{model.get("tuners")}')
    lines.append('')

  auth_rows = tshark(pcap, 'http.authorization', 'frame.number', 'http.request.method',
                     'http.request.uri', 'http.authorization', 'http.user_agent')
  tokens = {}
  for row in auth_rows:
    frame, method, uri, auth, ua = parse_row(row, 5)
    if not auth.startswith('tablo:'):
      continue
    _, token, request_hash = auth.split(':', 2)
    entry = tokens.setdefault(token, {'count': 0, 'hashes': set()})
    entry['count'] += 1
    entry['hashes'].add(request_hash)

  lines.append('DEVICE TOKENS')
  lines.append('-' * 40)
  for token, data in tokens.items():
    lines.append(f'token: {token}')
    lines.append(f'  used in {data["count"]} authenticated requests')
    lines.append(f'  unique hashes: {len(data["hashes"])}')
  lines.append('')

  client_rows = tshark(pcap, 'http.request.uri contains "device_id"', 'http.request.uri', 'http.user_agent')
  if client_rows:
    uri, ua = parse_row(client_rows[0], 2)
    lines.append('CLIENT DEVICE')
    lines.append('-' * 40)
    for pattern, label in [(r'device_id=([^&]+)', 'device_id'), (r'device_type=([^&]+)', 'device_type'),
                           (r'client_version=([^&]+)', 'client_version'), (r'client_build=([^&]+)', 'client_build')]:
      match = re.search(pattern, uri)
      if match:
        lines.append(f'{label + ":":14}{match.group(1)}')
    lines.append(f'user_agent:  {ua}')
    lines.append('')

  session_rows = tshark(pcap, 'http.request.uri contains "player/sessions"', 'http.request.uri')
  session_ids = sorted({
    match.group(1) for row in session_rows
    for match in [re.search(r'/player/sessions/([a-f0-9-]{36})', row.split('\t')[0])] if match
  })
  if session_ids:
    lines.append('PLAYER SESSIONS')
    lines.append('-' * 40)
    for session_id in session_ids:
      lines.append(f'session_id: {session_id}')
    lines.append('')

  stream_rows = tshark(pcap, 'http.host contains ":80"', 'http.request.uri')
  stream_tokens = defaultdict(set)
  for row in stream_rows:
    uri = row.split('\t')[0]
    if '?' not in uri:
      continue
    path, query = uri.split('?', 1)
    stream_tokens[path].add(query.split('&')[0])
  if stream_tokens:
    lines.append('STREAM TOKENS')
    lines.append('-' * 40)
    for path in sorted(stream_tokens):
      lines.append(f'{path}:')
      for token in sorted(stream_tokens[path]):
        lines.append(f'  {token}')
    lines.append('')

  lines.append('AUTHENTICATION LOG')
  lines.append('-' * 40)
  lines.append('Format: [frame] METHOD URI')
  lines.append('        Authorization: tablo:{token}:{hash}')
  lines.append('')
  for row in auth_rows:
    frame, method, uri, auth, ua = parse_row(row, 5)
    if not auth:
      continue
    lines.append(f'[{frame}] {method} {uri}')
    lines.append(f'  {auth}')
    lines.append('')

  Path(output_path).write_text('\n'.join(lines), encoding='utf-8')


def prune_unused_schemas(components_schemas, paths):
  payload = json.dumps({'paths': paths, 'schemas': components_schemas})
  used = set(re.findall(r'#/components/schemas/([A-Za-z0-9_]+)', payload))
  expanded = set(used)
  changed = True
  while changed:
    changed = False
    for name in list(expanded):
      ref = components_schemas.get(name, {}).get('$ref', '')
      match = re.search(r'#/components/schemas/([A-Za-z0-9_]+)', ref)
      if match and match.group(1) not in expanded:
        expanded.add(match.group(1))
        changed = True
  for name in list(components_schemas):
    if name not in expanded:
      del components_schemas[name]


def new_endpoint(method, norm, port):
  return {
    'method': method, 'path': norm, 'port': port,
    'status_codes': defaultdict(int), 'content_types': set(),
    'query_params': defaultdict(set), 'response_schemas': [],
    'request_schemas': [], 'has_auth': False, 'observed_count': 0,
    'source_pcaps': set(),
  }


def finalize_endpoint(endpoint):
  merged = {}
  for schema in endpoint['response_schemas']:
    merged = merge_schemas(merged, schema)
  endpoint['merged_response'] = merged
  req_merged = {}
  for schema in endpoint['request_schemas']:
    req_merged = merge_schemas(req_merged, schema)
  endpoint['merged_request'] = req_merged


def merge_endpoint_dicts(dicts):
  merged = {}
  for endpoints in dicts:
    for key, ep in endpoints.items():
      if key not in merged:
        merged[key] = new_endpoint(ep['method'], ep['path'], ep['port'])
        merged[key]['source_pcaps'] = set(ep.get('source_pcaps', set()))
      target = merged[key]
      target['observed_count'] += ep['observed_count']
      target['has_auth'] = target['has_auth'] or ep['has_auth']
      target['content_types'].update(ep['content_types'])
      target['source_pcaps'].update(ep.get('source_pcaps', set()))
      for code, count in ep['status_codes'].items():
        target['status_codes'][code] += count
      for qname, qvals in ep['query_params'].items():
        target['query_params'][qname].update(qvals)
      target['response_schemas'].extend(ep['response_schemas'])
      target['request_schemas'].extend(ep['request_schemas'])
  for ep in merged.values():
    finalize_endpoint(ep)
  return merged


def extract_endpoints(pcap):
  pcap_name = Path(pcap).name
  requests_by_key = defaultdict(list)
  for row in tshark(pcap, 'http.request', 'http.host', 'http.request.method', 'http.request.uri',
                    'http.authorization', 'http.user_agent', 'http.content_type', 'http.file_data'):
    host, method, uri, auth, ua, content_type, body_hex = parse_row(row, 7)
    if not method or not uri:
      continue
    port = host.rsplit(':', 1)[-1]
    norm = normalize_path(uri, port)
    if not is_valid_path(norm):
      continue
    body = None
    if body_hex:
      try:
        raw = decode_http_body(body_hex)
        if raw and raw[:1] == b'{':
          body = json.loads(raw)
      except (ValueError, json.JSONDecodeError):
        pass
    requests_by_key[(method, norm, port)].append({
      'uri': uri, 'auth': auth, 'ua': ua, 'body': body, 'content_type': content_type
    })

  uri_to_method = {}
  for (method, norm, port), reqs in requests_by_key.items():
    for req in reqs:
      uri_to_method[(req['uri'], port)] = method

  endpoints = {}
  for row in tshark(pcap, 'http.response', 'http.host', 'http.request.uri', 'http.response.code',
                    'http.content_type', 'http.file_data'):
    host, uri, code, content_type, body_hex = parse_row(row, 5)
    port = host.rsplit(':', 1)[-1] if host else '8887'
    if not uri:
      if code == '206' and port == '80':
        uri = '/stream/segw.ts'
      else:
        continue
    norm = normalize_path(uri, port)
    if not is_valid_path(norm):
      continue
    method = uri_to_method.get((uri, port), 'GET')
    key = (method, norm, port)
    endpoint = endpoints.setdefault(key, new_endpoint(method, norm, port))
    endpoint['source_pcaps'].add(pcap_name)
    endpoint['observed_count'] += 1
    endpoint['status_codes'][code] += 1
    if content_type:
      endpoint['content_types'].add(content_type.split(';')[0].strip())
    for key_name, values in parse_query(uri).items():
      endpoint['query_params'][key_name].update(values)
    if body_hex and code in ('200', '204', '206'):
      try:
        raw = decode_http_body(body_hex)
        if raw and raw[:1] in (b'{', b'['):
          endpoint['response_schemas'].append(infer_schema(json.loads(raw)))
      except (ValueError, json.JSONDecodeError):
        pass

  for key, reqs in requests_by_key.items():
    endpoint = endpoints.setdefault(key, new_endpoint(key[0], key[1], key[2]))
    endpoint['source_pcaps'].add(pcap_name)
    for req in reqs:
      if req['auth']:
        endpoint['has_auth'] = True
      for key_name, values in parse_query(req['uri']).items():
        endpoint['query_params'][key_name].update(values)
      if req['body']:
        endpoint['request_schemas'].append(infer_schema(req['body']))

  for endpoint in endpoints.values():
    finalize_endpoint(endpoint)
  return endpoints


def build_spec_from_endpoints(endpoints, tablo_host, device_info, source_pcaps):
  components_schemas = {}
  paths = {}

  def add_schema(name, schema):
    cleaned = sanitize_schema(schema)
    if cleaned.get('properties') or cleaned.get('type') == 'array' or cleaned.get('$ref'):
      if name in components_schemas and name != 'PlayerSession':
        components_schemas[name] = sanitize_schema(merge_schemas(components_schemas[name], cleaned))
      else:
        components_schemas[name] = cleaned

  for (method, path, port), endpoint in sorted(endpoints.items()):
    paths.setdefault(path, {})
    op_id = re.sub(r'_+', '_', f"{method.lower()}_{re.sub(r'[{}]', '', path).strip('/').replace('/', '_')}")

    params = []
    for pname in re.findall(r'\{(\w+)\}', path):
      desc = {
        'id': 'Numeric object identifier',
        'airingId': 'Guide airing identifier (LH-CEP... or LH-CSH... format)',
        'channelId': 'Channel identifier (S{station}_{major}_{minor} format)',
        'sessionId': 'Player session UUID returned by /watch',
        'showId': 'Series/show identifier (C{num}_SHOW_SH{num} format)',
      }.get(pname, pname)
      params.append({'name': pname, 'in': 'path', 'required': True,
                     'schema': {'type': 'string'}, 'description': desc})
    for qname, qvals in sorted(endpoint['query_params'].items()):
      params.append(query_param_spec(qname, qvals))

    pcap_sources = sorted(endpoint.get('source_pcaps', set()))
    operation = {
      'operationId': op_id,
      'summary': PATH_DESCRIPTIONS.get(path, f'{method} {path}'),
      'tags': [path.strip('/').split('/')[0]],
      'x-observed-count': endpoint['observed_count'],
    }
    if pcap_sources:
      operation['x-source-pcaps'] = pcap_sources
    if params:
      operation['parameters'] = params
    if port == '80':
      operation['servers'] = [{'url': 'http://{tabloHost}:80', 'variables': {
        'tabloHost': {'default': tablo_host}
      }}]
    if endpoint['has_auth']:
      operation['security'] = [{'TabloAuth': []}]
    if path == '/notifications/stream':
      operation['responses'] = {
        '200': {
          'description': 'Long-lived event stream (response body not fully captured)',
          'content': {'text/event-stream': {'schema': {'type': 'string'}}}
        }
      }
    elif endpoint['merged_request']:
      rname = schema_name(method, path, 'Request')
      add_schema(rname, endpoint['merged_request'])
      operation['requestBody'] = {
        'required': True,
        'content': {'application/json': {'schema': {'$ref': f'#/components/schemas/{rname}'}}}
      }

    responses = operation.get('responses', {})
    for code, count in sorted(endpoint['status_codes'].items()):
      response = {'description': f'HTTP {code} (observed {count}x in capture)'}
      if code == '204':
        responses[str(code)] = response
        continue
      if endpoint['merged_response'] and code == '200':
        rname = schema_name(method, path)
        add_schema(rname, endpoint['merged_response'])
        response['content'] = {'application/json': {'schema': {'$ref': f'#/components/schemas/{rname}'}}}
      elif path in STREAM_PATHS:
        response['content'] = {STREAM_PATHS[path]: {'schema': {'type': 'string', 'format': 'binary'}}}
      elif path.startswith('/images/'):
        response['content'] = {'image/jpeg': {'schema': {'type': 'string', 'format': 'binary'}}}
      elif code == '206':
        response['content'] = {'video/mp2t': {'schema': {'type': 'string', 'format': 'binary'}}}
      responses[str(code)] = response
    if not responses:
      responses['default'] = {'description': 'Response not captured in this trace'}
    operation['responses'] = responses
    paths[path][method.lower()] = operation

  watch_paths = (
    '/guide/channels/{channelId}/watch',
    '/recordings/series/episodes/{id}/watch',
  )
  player_session_schema = {}
  for watch_path in watch_paths:
    watch_key = ('POST', watch_path, '8887')
    if watch_key in endpoints and endpoints[watch_key].get('merged_response'):
      player_session_schema = merge_schemas(
        player_session_schema, endpoints[watch_key]['merged_response'])
  if player_session_schema:
    add_schema('PlayerSession', player_session_schema)
    for op_path in (*watch_paths, '/player/sessions/{sessionId}'):
      for op in paths.get(op_path, {}).values():
        response = op.get('responses', {}).get('200', {})
        if response.get('content', {}).get('application/json'):
          response['content']['application/json']['schema'] = {
            '$ref': '#/components/schemas/PlayerSession'
          }

  delete_session = paths.get('/player/sessions/{sessionId}', {}).get('delete')
  if delete_session:
    delete_session['responses'] = {
      '204': {'description': 'Session ended successfully'}
    }
    delete_session['summary'] = 'End an active player session'

  get_session = paths.get('/player/sessions/{sessionId}', {}).get('get')
  if get_session and '200' not in get_session.get('responses', {}):
    get_session['responses'] = {
      '200': {
        'description': 'Current player session state',
        'content': {
          'application/json': {
            'schema': {'$ref': '#/components/schemas/PlayerSession'}
          }
        }
      }
    }

  prune_unused_schemas(components_schemas, paths)

  model = device_info.get('model', {})
  return {
    'openapi': '3.1.0',
    'info': {
      'title': 'Tablo TV 4th Generation Local API',
      'description': textwrap.dedent('''
        Reverse-engineered OpenAPI specification for the Tablo TV 4th Generation local HTTP API.

        See tablo-api-guide.md for usage, authentication details, and playback flow.
      ''').strip(),
      'version': '0.2.0',
      'x-source-pcaps': source_pcaps,
      'x-captured-device': {
        'model': model.get('name'),
        'firmware': device_info.get('version'),
        'server_id': device_info.get('server_id'),
      },
    },
    'servers': [{'url': 'http://{tabloHost}:8887', 'variables': {
      'tabloHost': {'default': tablo_host, 'description': 'Tablo LAN IP address'}
    }}],
    'tags': [
      {'name': 'server'}, {'name': 'settings'}, {'name': 'guide'}, {'name': 'views'},
      {'name': 'recordings'}, {'name': 'player'}, {'name': 'images'},
      {'name': 'notifications'}, {'name': 'stream'},
    ],
    'paths': paths,
    'components': {
      'securitySchemes': {
        'TabloAuth': {
          'type': 'apiKey', 'in': 'header', 'name': 'Authorization',
          'description': 'Format: `tablo:{deviceToken}:{requestHash}`'
        }
      },
      'schemas': components_schemas,
    },
  }


def build_spec(pcaps, tablo_host):
  all_endpoints = []
  device_info = {}
  for pcap in pcaps:
    all_endpoints.append(extract_endpoints(pcap))
    info = extract_device_info(pcap)
    if info:
      device_info = info
  endpoints = merge_endpoint_dicts(all_endpoints)
  source_pcaps = [Path(p).name for p in pcaps]
  return build_spec_from_endpoints(endpoints, tablo_host, device_info, source_pcaps)


def main():
  parser = argparse.ArgumentParser(
    description='Generate or merge Tablo OpenAPI spec from one or more HTTP PCAP captures')
  parser.add_argument('pcaps', type=Path, nargs='+', help='Input PCAP file(s) (HTTP traffic to Tablo)')
  parser.add_argument('-o', '--output', type=Path, help='Output OpenAPI YAML path')
  parser.add_argument('--credentials', type=Path, help='Optional credentials extract from last PCAP')
  parser.add_argument('--host', default=None, help='Tablo LAN IP (default: from /server/info response)')
  args = parser.parse_args()

  for pcap in args.pcaps:
    if not pcap.exists():
      print(f'PCAP not found: {pcap}', file=sys.stderr)
      return 1

  device_info = {}
  for pcap in args.pcaps:
    info = extract_device_info(pcap)
    if info:
      device_info = info
  tablo_host = args.host or device_info.get('local_address', '192.168.1.1')
  output = args.output or args.pcaps[0].with_name('tablo-api.openapi.yaml')

  try:
    import yaml
  except ImportError:
    print('PyYAML is required: pip install pyyaml', file=sys.stderr)
    return 1

  spec = build_spec(args.pcaps, tablo_host)
  with output.open('w', encoding='utf-8') as handle:
    yaml.dump(spec, handle, sort_keys=False, allow_unicode=True, default_flow_style=False,
              width=120, default_style=None)

  print(f'Wrote {output}')
  print(f'  paths: {len(spec["paths"])}, schemas: {len(spec["components"]["schemas"])}')
  print(f'  source pcaps: {", ".join(spec["info"]["x-source-pcaps"])}')

  if args.credentials:
    extract_credentials(args.pcaps[-1], args.credentials)
    print(f'Wrote {args.credentials}')

  return 0


if __name__ == '__main__':
  raise SystemExit(main())
