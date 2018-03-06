/*
 * Copyright (c) 2015-2017 LabKey Corporation. All rights reserved. No portion of this work may be reproduced in
 * any form or by any electronic or mechanical means without written permission from LabKey Corporation.
 */
const webpack = require("webpack");
const ExtractTextPlugin = require('extract-text-webpack-plugin');
const constants = require("./constants");

module.exports = {
    context: constants.context(__dirname),

    devtool: 'source-map',

    entry: {
        'i': [
            //'./src/theme/style.js',
            './src/client/index.tsx'
        ]
    },

    output: {
        path: constants.outputPath(__dirname),
        publicPath: './', // allows context path to resolve in both js/css
        filename: "[name].js"
    },

    module: {
        rules: constants.loaders.STYLE_LOADERS.concat(constants.loaders.TYPESCRIPT_LOADERS)
    },

    resolve: {
        extensions: constants.extensions.TYPESCRIPT
    },

    plugins: [
        new webpack.IgnorePlugin(/^\.\/locale$/, /moment$/),
        // https://github.com/moment/moment/issues/2416

        new webpack.DefinePlugin({
            'process.env.NODE_ENV': '"production"'
        }),
        new ExtractTextPlugin({
            allChunks: true,
            filename: '[name].css'
        }),
        new webpack.optimize.UglifyJsPlugin({
            sourceMap: true
        })
    ]
};


