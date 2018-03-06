/*
 * Copyright (c) 2015-2017 LabKey Corporation. All rights reserved. No portion of this work may be reproduced in
 * any form or by any electronic or mechanical means without written permission from LabKey Corporation.
 */
import * as React from 'react'
import * as ReactDOM from 'react-dom'
import * as GraphiQL from 'graphiql'
import $ from 'jquery'

function graphQLFetcher(graphQLParams) {
    return new Promise((resolve, reject) => {
        LABKEY.Ajax.request({
            url: LABKEY.ActionURL.buildURL('graphql', 'i.api'),
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            jsonData: graphQLParams,
            success: LABKEY.Utils.getCallbackWrapper((json) => {
                resolve(json);
            })
        });
    });
}

$(() => ReactDOM.render(<iGraphiQL fetcher={graphQLFetcher}/>, document.getElementById('app')));

