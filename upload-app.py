#!/usr/bin/python
#
# Copyright 2014 Google Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Lists all the apks for a given app.

based on https://github.com/googlesamples/android-play-publisher-api/blob/master/v3/python/basic_upload_apks.py
"""

import argparse
import os

from apiclient.discovery import build
import google.auth

# Declare command-line flags.
argparser = argparse.ArgumentParser(add_help=False)
argparser.add_argument('package_name',
                       help='The package name. Example: com.android.sample')
argparser.add_argument('version_name',
                        help="the name of the new version")
argparser.add_argument('aab_file',
                       nargs='?',
                       default="app-release.aab",
                       help='The path to the AAB file to upload.')
argparser.add_argument('--track',
                       choices=["alpha", "beta", "production", "rollout"],
                       default="alpha",
                       help="the track to release the APK on")


def main():
    # Process flags and read their values.
    flags = argparser.parse_args()

    credentials, project = google.auth.default()
    service = build('androidpublisher', 'v3', credentials=credentials)

    package_name = flags.package_name

    edit_request = service.edits().insert(body={}, packageName=package_name)
    result = edit_request.execute()
    edit_id = result['id']

    bundle_response = service.edits().bundles().upload(
        editId=edit_id,
        packageName=package_name,
        media_mime_type='application/octet-stream',
        media_body=flags.aab_file).execute()

    print('Version code %d has been uploaded' % bundle_response['versionCode'])

    track_response = service.edits().tracks().update(
        editId=edit_id,
        track=flags.track,
        packageName=package_name,
        body={u'releases': [{
            u'name': flags.version_name,
            u'versionCodes': [str(bundle_response['versionCode'])],
            u'status': u'completed',
        }]}).execute()

    print('Track %s is set with releases: %s' % (track_response['track'], str(track_response['releases'])))

    try:
        commit_request = service.edits().commit(editId=edit_id, packageName=package_name).execute()
    except googleapiclient.errors.HttpError as e:
        if e.status_code == 400:
            print("error submitting to google play: %s", e._get_reason())
            commit_request = service.edits(editId=edit_id, packageName=package_name, changesNotSentForReview="true").execute()

    print('Edit "%s" has been committed' % (commit_request['id']))

if __name__ == '__main__':
    main()
